package com.dragon.rcamera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import com.dragon.rcamera.data.CameraStore
import com.dragon.rcamera.rtp.RtpSender
import com.dragon.rcamera.websocket.WsMessage
import com.dragon.rcamera.websocket.WebSocketManager
import com.google.gson.JsonObject
import org.java_websocket.WebSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RemoteCameraService : Service(), LifecycleOwner {

    companion object {
        const val CHANNEL_ID = "remote_camera_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_WS = "action_start_ws"
        const val ACTION_STOP_WS = "action_stop_ws"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PASSWORD = "extra_password"
        private const val TAG = "RemoteCameraService"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var wrapperSurfaceProvider: Preview.SurfaceProvider? = null
    private var useExternalPreview = false  // true when Activity uses getSurfaceProvider() mode
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()
    private val wsManager = WebSocketManager()
    private var isCameraOpen = false

    // WebSocket configuration
    private var wsPort: Int = -1
    private var wsPassword: String? = null

    // RTP and encoding
    private val rtpSender = RtpSender()
    private var encoder: MediaCodec? = null
    private var isEncoding = false
    private val encodingLock = Object()
    private var encoderWidth = 720
    private var encoderHeight = 1280
    private var frameRotationDegrees = 90

    // OpenGL/EGL for rendering camera frames to preview and encoder surfaces
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglPBufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE  // offscreen surface for GL init
    private var previewSurface: Surface? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraOesTextureId: Int = 0

    // OpenGL state
    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var textureHandle: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private val vertexCoords = floatArrayOf(
        -1f, -1f,  1f, -1f, -1f,  1f,  1f,  1f
    )
    private val texCoords = floatArrayOf(
        1f, 1f,  1f, 0f,  0f, 1f,  0f, 0f
    )

    private var renderThread: RenderThread? = null
    private val isFrameAvailable = AtomicBoolean(false)
    private val frameLock = Object()

    // Track client RTP ports: clientId -> rtpPort
    private val clientRtpPorts = mutableMapOf<String, Int>()
    // Track client addresses: clientId -> hostAddress
    private val clientAddresses = mutableMapOf<String, String>()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RemoteCameraService = this@RemoteCameraService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createNotificationChannel()
        Log.d(TAG, "Service show notification (id=$NOTIFICATION_ID)")
        startForeground(NOTIFICATION_ID, buildNotification())

        acquireWakeLock()
        cameraExecutor = Executors.newSingleThreadExecutor()
        initCamera()

        // Initialize and start WebSocket server based on stored configuration
        val store = CameraStore(this)
        wsPort = store.getServerPort()
        wsPassword = store.getServerPassword()
        startWebSocketIfConfigured()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: startId=$startId")

        // Update WebSocket configuration if provided
        intent?.let {
            val newPort = it.getIntExtra(EXTRA_PORT, -1)
            val newPassword = it.getStringExtra(EXTRA_PASSWORD)

            var configChanged = false
            if (newPort > 0 && newPort != wsPort) {
                wsPort = newPort
                configChanged = true
            }
            if (newPassword != null && newPassword != wsPassword) {
                wsPassword = newPassword
                configChanged = true
            }

            // Restart WebSocket if configuration changed
            if (configChanged) {
                startWebSocketIfConfigured()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopWebSocketServer()
        stopEncodingAndRtp()
        releaseEncoderSurface()
        releasePreviewSurface()
        releaseOpenGl()
        unbindPreview()
        camera = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor.shutdown()
        releaseWakeLock()
        Log.d(TAG, "Service remove notification (id=$NOTIFICATION_ID)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Explicitly cancel the notification in case stopForeground doesn't remove it
        // (e.g., due to async callbacks re-posting it after stopForeground)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /**
     * Set surface provider for preview.
     * Called when Activity becomes visible.
     * The provider is wrapped internally to redirect rendering through OpenGL.
     */
    fun setSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        bindCamera()
    }

    /**
     * Get a surface provider for Activity to use with Preview.setSurfaceProvider().
     * This allows Activity to create its own Preview instance while still using
     * the Service's OpenGL rendering pipeline.
     *
     * Usage:
     *   val preview = Preview.Builder().build()
     *   preview.setSurfaceProvider(service.getSurfaceProvider())
     *   service.bindPreviewUseCase(preview)
     */
    fun getSurfaceProvider(): Preview.SurfaceProvider {
        return Preview.SurfaceProvider { request ->
            val resolution = request.resolution

            // Initialize OpenGL if not already done (creates SurfaceTexture)
            if (!initializeOpenGl()) {
                Log.e(TAG, "Failed to initialize OpenGL for getSurfaceProvider()")
                return@SurfaceProvider
            }

            val surfaceTexture = cameraSurfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "No SurfaceTexture available after OpenGL init")
                return@SurfaceProvider
            }

            surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(surfaceTexture)

            // Provide surface to CameraX
            request.provideSurface(surface, cameraExecutor) { result ->
                Log.d(TAG, "Camera surface released via getSurfaceProvider()")
            }

            // Start render thread if not running
            startRenderThreadIfNeeded()
        }
    }

    /**
     * Bind an externally-created Preview use case to the camera.
     * Call this after setting the surface provider via getSurfaceProvider().
     */
    /**
     * Bind an externally-created Preview use case to the camera.
     * Call this after setting the surface provider via getSurfaceProvider().
     */
    fun bindPreviewUseCase(previewUseCase: Preview) {
        useExternalPreview = true
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { bindPreviewUseCase(previewUseCase) }
            return
        }

        val provider = cameraProvider
        if (provider == null) {
            // cameraProvider not ready yet, retry after a delay
            Handler(Looper.getMainLooper()).postDelayed({ bindPreviewUseCase(previewUseCase) }, 100)
            return
        }
        if (isCameraOpen) return

        try {
            provider.unbindAll()

            // Initialize OpenGL if not done
            if (!initializeOpenGl()) {
                Log.e(TAG, "Failed to initialize OpenGL for bindPreviewUseCase")
                return
            }

            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase
            )
            isCameraOpen = true
            Log.d(TAG, "Camera bound with external preview use case (isEncoding=$isEncoding)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind preview use case", e)
        }
    }

    /**
     * Set preview surface directly for OpenGL rendering.
     * Call this when Activity's TextureView/SurfaceView surface is ready.
     * This method can be called in TextureView.SurfaceTextureListener.onSurfaceTextureAvailable()
     * or SurfaceView.SurfaceHolder.Callback.surfaceCreated().
     */
    fun setPreviewSurface(surface: Surface?) {
        if (surface == null) {
            releasePreviewSurface()
            return
        }

        if (!initializeOpenGl()) {
            Log.e(TAG, "Failed to initialize OpenGL")
            return
        }

        // Only release the old EGL surface, do NOT stop the render thread.
        // releasePreviewSurface() would stop the render thread when there's no
        // encoder surface, and then we can't restart it (Java Thread cannot be
        // restarted after stop). Since we're about to set a new preview surface,
        // the render thread should keep running.
        try {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(previewEglSurface)
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing old preview EGL surface", e)
        }
        previewSurface = null

        try {
            previewSurface = surface
            previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null, 0)

            // Start render thread if not running
            startRenderThreadIfNeeded()

            Log.d(TAG, "Preview surface set via setPreviewSurface()")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set preview surface", e)
            releasePreviewSurface()
        }
    }

    /**
     * Clear surface provider.
     * Called when Activity is paused/destroyed.
     */
    fun clearSurfaceProvider() {
        surfaceProvider = null
        wrapperSurfaceProvider = null
        useExternalPreview = false
        releasePreviewSurface()
        // Check if we should close camera
        if (!hasEncoderSurface() && !hasPreviewSurface()) {
            closeCamera()
        }
    }

    // ===== WebSocket Server =====

    /**
     * Start WebSocket server if port and password are configured.
     * If server is already running, it will be restarted with new configuration.
     */
    private fun startWebSocketIfConfigured() {
        if (wsPort <= 0 || wsPassword.isNullOrEmpty()) {
            Log.d(TAG, "WebSocket not configured, skipping start (port=$wsPort, password=${if (wsPassword.isNullOrEmpty()) "null" else "***"})")
            return
        }

        // Stop existing server if running
        if (wsManager.isServerRunning()) {
            Log.d(TAG, "Stopping existing WebSocket server before restart")
            wsManager.stopServer()
        }

        startWebSocketServer(wsPort, wsPassword!!)
    }

    fun startWebSocketServer(port: Int, password: String) {
        Log.d(TAG, "WS server starting: port=$port")
        wsManager.onServerMessageReceived = { conn, message ->
            Log.d(TAG, "WS recv from ${conn.remoteSocketAddress}: $message")
            handleClientMessage(conn, message)
        }
        wsManager.onServerClientConnected = { conn ->
            handleClientAuthenticated(conn)
            updateNotification()
        }
        wsManager.onServerClientDisconnected = { conn ->
            handleClientDisconnected(conn)
            updateNotification()
        }
        val result = wsManager.startServer(port, password)
        Log.d(TAG, "WS server started: result=$result")
        updateNotification()
    }

    fun stopWebSocketServer() {
        Log.d(TAG, "WS server stopping")
        stopEncodingAndRtp()
        wsManager.stopServer()
        Log.d(TAG, "WS server stopped")
        updateNotification()
    }

    fun isWsServerRunning(): Boolean = wsManager.isServerRunning()

    fun getWsServerUrl(): String? = wsManager.getServerUrl()

    fun getWsServerUrlForAddress(addr: String): String? = wsManager.getServerUrlForAddress(addr)

    fun getWsServerIpInfo(): com.dragon.rcamera.websocket.IpInfo? = wsManager.getServerIpInfo()

    fun getWsClientCount(): Int = wsManager.getServerClientCount()

    private fun handleClientAuthenticated(conn: WebSocket) {
        val host = conn.remoteSocketAddress?.address?.hostAddress ?: return
        val clientId = conn.remoteSocketAddress.toString()
        val isIpv6 = host.contains(":")

        clientAddresses[clientId] = host
        Log.d(TAG, "Client authenticated: $clientId from $host (IPv${if (isIpv6) "6" else "4"})")

        if (wsManager.getServerClientCount() == 1) {
            startEncodingAndRtp()
        }
    }

    private fun handleClientDisconnected(conn: WebSocket) {
        val clientId = conn.remoteSocketAddress.toString()

        rtpSender.removeDestination(clientId)
        clientRtpPorts.remove(clientId)
        clientAddresses.remove(clientId)

        if (wsManager.getServerClientCount() == 0) {
            stopEncodingAndRtp()
        }
    }

    private fun handleClientMessage(conn: WebSocket, message: WsMessage) {
        val clientId = conn.remoteSocketAddress.toString()

        when (message.action) {
            WsMessage.ACTION_OPEN_CAMERA -> {
                openCamera()
                val response = message.makeResponse(true, mapOf("camera_open" to true))
                wsManager.sendToClient(conn, response)
                Log.d(TAG, "WS send to ${conn.remoteSocketAddress}: $response")
                broadcastCameraStatus()
            }
            WsMessage.ACTION_CLOSE_CAMERA -> {
                closeCamera()
                val response = message.makeResponse(true, mapOf("camera_open" to false))
                wsManager.sendToClient(conn, response)
                Log.d(TAG, "WS send to ${conn.remoteSocketAddress}: $response")
                broadcastCameraStatus()
            }
            WsMessage.ACTION_PING -> {
                val response = WsMessage(
                    id = message.id,
                    type = WsMessage.TYPE_RESPONSE,
                    action = WsMessage.ACTION_PONG,
                    payload = JsonObject().apply { addProperty("camera_open", isCameraOpen) }
                )
                wsManager.sendToClient(conn, response)
                Log.d(TAG, "WS send to ${conn.remoteSocketAddress}: $response")
            }
            WsMessage.ACTION_START_RTP -> {
                val rtpPort = message.payload.get("rtp_port")?.asInt ?: return
                val host = conn.remoteSocketAddress?.address?.hostAddress ?: return
                val isIpv6 = host.contains(":")

                clientRtpPorts[clientId] = rtpPort
                rtpSender.addDestination(clientId, host, rtpPort)
                Log.d(TAG, "RTP destination added: $host:$rtpPort (IPv${if (isIpv6) "6" else "4"}), sender socket bound to ${rtpSender.getLocalPort()}")

                val needSwap = frameRotationDegrees == 90 || frameRotationDegrees == 270
                val encodedWidth = if (needSwap) encoderHeight else encoderWidth
                val encodedHeight = if (needSwap) encoderWidth else encoderHeight

                val response = message.makeResponse(true, mapOf(
                    "rtp_started" to true,
                    "server_rtp_port" to (rtpSender.getLocalPort()),
                    "video_width" to encodedWidth,
                    "video_height" to encodedHeight
                ))
                wsManager.sendToClient(conn, response)
                Log.d(TAG, "WS send to ${conn.remoteSocketAddress}: $response")

                if (!isEncoding) {
                    startEncodingAndRtp()
                }
            }
            WsMessage.ACTION_STOP_RTP -> {
                rtpSender.removeDestination(clientId)
                clientRtpPorts.remove(clientId)

                val response = message.makeResponse(true, mapOf("rtp_stopped" to true))
                wsManager.sendToClient(conn, response)
                Log.d(TAG, "WS send to ${conn.remoteSocketAddress}: $response")

                if (!rtpSender.hasDestinations()) {
                    stopEncodingAndRtp()
                }
            }
        }
    }

    private fun broadcastCameraStatus() {
        val msg = WsMessage(
            type = WsMessage.TYPE_EVENT,
            action = WsMessage.ACTION_CAMERA_STATUS,
            payload = JsonObject().apply { addProperty("camera_open", isCameraOpen) }
        )
        wsManager.broadcastToClients(msg)
        Log.d(TAG, "WS broadcast: $msg")
    }

    // ===== OpenGL =====

    private fun initializeOpenGl(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return true

        synchronized(this) {
            // Double-check after acquiring lock
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) return true

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL display")
            return false
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL")
            return false
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)

            // First try with EGL_RECORDABLE_ANDROID for encoder support
            var configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT or 0x314,  // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
            )

            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            Log.w(TAG, "EGL config with EGL_RECORDABLE_ANDROID not found, trying without")
            // Fallback without EGL_RECORDABLE_ANDROID
            configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                Log.e(TAG, "Unable to choose EGL config")
                return false
            }
            }
            eglConfig = configs[0]

            val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Unable to create EGL context")
            return false
            }

            // Create a PBuffer surface so we can make the context current for GL operations
            val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
            )
            eglPBufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
            if (eglPBufferSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Unable to create EGL PBuffer surface")
            return false
            }

            // Make context current before any GL calls
            if (!EGL14.eglMakeCurrent(eglDisplay, eglPBufferSurface, eglPBufferSurface, eglContext)) {
            Log.e(TAG, "Unable to make EGL context current")
            return false
            }

            // Setup buffers
            vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexCoords)
            vertexBuffer?.position(0)

            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            texCoordBuffer?.position(0)

            // Create OES texture for camera
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            cameraOesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create shader program
            if (!setupShaders()) {
            Log.e(TAG, "Failed to setup shaders")
            return false
            }

            // Create SurfaceTexture
            cameraSurfaceTexture = SurfaceTexture(cameraOesTextureId)
            cameraSurfaceTexture?.setOnFrameAvailableListener {
            synchronized(frameLock) {
                isFrameAvailable.set(true)
                frameLock.notifyAll()
            }
            }

            // Release context from this thread so RenderThread can make it current
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            Log.d(TAG, "OpenGL initialized")
            return true
        } // synchronized
    }

    private fun setupShaders(): Boolean {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertexShader, """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent())
        GLES20.glCompileShader(vertexShader)

        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragmentShader, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent())
        GLES20.glCompileShader(fragmentShader)

        shaderProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(shaderProgram, vertexShader)
        GLES20.glAttachShader(shaderProgram, fragmentShader)
        GLES20.glLinkProgram(shaderProgram)

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(shaderProgram, "uTexture")

        return true
    }

    private fun releaseOpenGl() {
        stopRenderThread()

        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        if (cameraOesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraOesTextureId), 0)
            cameraOesTextureId = 0
        }
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null

        if (eglPBufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglPBufferSurface)
            eglPBufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        Log.d(TAG, "OpenGL released")
    }

    private fun releasePreviewSurface() {
        try {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(previewEglSurface)
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            previewSurface = null

            // Stop render thread if no surfaces remain
            if (!hasEncoderSurface()) {
                stopRenderThread()
            }

            Log.d(TAG, "Preview surface released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing preview surface", e)
        }
    }

    /**
     * Set encoder surface for MediaCodec input.
     */
    fun setEncoderSurface(surface: Surface?) {
        if (surface == null) {
            releaseEncoderSurface()
            return
        }

        if (!initializeOpenGl()) {
            Log.e(TAG, "Failed to initialize OpenGL")
            return
        }

        releaseEncoderSurface()

        try {
            encoderSurface = surface
            encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null, 0)

            // Start render thread if not running
            startRenderThreadIfNeeded()

            Log.d(TAG, "Encoder surface set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set encoder surface", e)
            releaseEncoderSurface()
        }
    }

    private fun releaseEncoderSurface() {
        try {
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(encoderEglSurface)
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            encoderSurface = null

            // Stop render thread if no surfaces
            if (!hasPreviewSurface()) {
                stopRenderThread()
            }

            Log.d(TAG, "Encoder surface released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder surface", e)
        }
    }

    fun hasPreviewSurface(): Boolean = previewSurface != null
    fun hasEncoderSurface(): Boolean = encoderSurface != null
    fun hasAnySurface(): Boolean = hasPreviewSurface() || hasEncoderSurface()

    /**
     * Start render thread if not already running.
     * Creates a new RenderThread instance if the previous one has stopped
     * (Java Thread objects cannot be restarted).
     */
    private fun startRenderThreadIfNeeded() {
        val current = renderThread
        if (current != null && current.isRunning) return

        renderThread = RenderThread().also { it.start() }
    }

    /**
     * Stop the render thread and wait for it to finish.
     */
    private fun stopRenderThread() {
        renderThread?.stopRendering()
        try {
            renderThread?.join(1000)
        } catch (_: Exception) {}
        renderThread = null
    }

    private fun makeCurrent(eglSurface: EGLSurface): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun renderFrame(eglSurface: EGLSurface) {
        if (!makeCurrent(eglSurface)) {
            Log.e(TAG, "renderFrame: Failed to make EGL context current")
            return
        }

        // Query the actual surface dimensions and set viewport accordingly
        val surfaceWidth = IntArray(1)
        val surfaceHeight = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
        GLES20.glViewport(0, 0, surfaceWidth[0], surfaceHeight[0])

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(shaderProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        // Set presentation time for encoder surface from camera frame timestamp
        if (eglSurface == encoderEglSurface) {
            val timestamp = cameraSurfaceTexture?.timestamp ?: 0L
            if (timestamp > 0) {
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
            }
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Background thread for rendering camera frames.
     */
    private inner class RenderThread : Thread("CameraRenderThread") {
        @Volatile
        var isRunning = false
            private set

        override fun run() {
            isRunning = true
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_VIDEO)
            Log.d(TAG, "RenderThread started")

            while (isRunning) {
                try {
                    // Wait for frame
                    synchronized(frameLock) {
                        while (!isFrameAvailable.get() && isRunning) {
                            try {
                                frameLock.wait(100)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    }

                    if (!isRunning) break
                    isFrameAvailable.set(false)

                    // Make EGL context current before updateTexImage
                    // (SurfaceTexture.updateTexImage requires the EGL context that created the texture to be current)
                    if (!makeCurrent(eglPBufferSurface)) {
                        Log.e(TAG, "RenderThread: failed to make EGL context current for updateTexImage")
                        continue
                    }

                    // Update texture from SurfaceTexture
                    cameraSurfaceTexture?.updateTexImage()

                    // Get the texture timestamp for debugging
                    val timestamp = cameraSurfaceTexture?.timestamp ?: 0L

                    // Render to all available surfaces
                    if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                        renderFrame(previewEglSurface)
                    }
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        renderFrame(encoderEglSurface)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "RenderThread loop error", e)
                    }
                }
            }

            Log.d(TAG, "RenderThread stopped")
            isRunning = false
        }

        fun stopRendering() {
            isRunning = false
            synchronized(frameLock) {
                isFrameAvailable.set(true)
                frameLock.notifyAll()
            }
        }
    }

    // ===== RTP / Encoding =====

    private fun startEncodingAndRtp() {
        synchronized(encodingLock) {
            if (isEncoding) return
            try {
                rtpSender.start()
                wsManager.setRtpPort(rtpSender.getLocalPort())
                createEncoderSurface()
                isEncoding = true
                Log.d(TAG, "Encoding and RTP started with surface input")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start encoding", e)
            }
        }
    }

    private fun stopEncodingAndRtp() {
        synchronized(encodingLock) {
            if (!isEncoding) return
            isEncoding = false
            releaseEncoderSurface()
            stopH264Encoder()
            rtpSender.stop()
            clientRtpPorts.clear()
            clientAddresses.clear()
            Log.d(TAG, "Encoding and RTP stopped")
        }
    }

    /**
     * Create encoder surface for MediaCodec input.
     */
    private fun createEncoderSurface() {
        Log.d(TAG, "MediaCodec creating encoder: ${encoderWidth}x${encoderHeight}")
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, encoderWidth, encoderHeight
        )
        // Required encoding parameters
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)  // 2 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // I-frame every 2 seconds
        // Use surface-based input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            Log.d(TAG, "MediaCodec configure: $format")
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = encoder?.createInputSurface()
            Log.d(TAG, "MediaCodec input surface created")
            encoder?.start()
            Log.d(TAG, "MediaCodec started")

            // Set encoder surface for OpenGL rendering
            encoderSurface?.let { surface ->
                setEncoderSurface(surface)
            }

            // Start output drain thread
            drainThread = Thread({ drainEncoderLoop() }, "EncoderDrainThread").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "MediaCodec encoder ready: ${encoderWidth}x${encoderHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec failed to create encoder", e)
            releaseEncoderSurface()
        }
    }

    private fun stopH264Encoder() {
        Log.d(TAG, "MediaCodec stopping encoder")
        try {
            drainThread?.join(1000)
        } catch (_: Exception) {}
        drainThread = null
        try {
            encoder?.stop()
            Log.d(TAG, "MediaCodec stopped")
            encoder?.release()
            Log.d(TAG, "MediaCodec released")
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec error stopping", e)
        }
        encoder = null
    }

    private var drainThread: Thread? = null

    private fun drainEncoderLoop() {
        Log.d(TAG, "MediaCodec drain loop started")
        val info = MediaCodec.BufferInfo()
        while (isEncoding) {
            try {
                val enc = encoder ?: break
                val outputBufferIndex = enc.dequeueOutputBuffer(info, 10000)
                if (outputBufferIndex >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val outputBuffer = enc.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        val data = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(data)
                        rtpSender.sendH264AccessUnit(data, data.size, info.presentationTimeUs, isConfig = isConfig)
                        if (isConfig) {
                            Log.d(TAG, "MediaCodec output SPS/PPS config: ${info.size} bytes, pts=${info.presentationTimeUs}")
                        }
                    }
                    enc.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "MediaCodec output format changed: ${enc.outputFormat}")
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(TAG, "MediaCodec drain error", e)
                }
            }
        }
        Log.d(TAG, "MediaCodec drain loop ended")
    }

    /**
     * Check if encoding should stop when encoder surface is removed.
     */
    private fun checkAndStopEncodingIfNeeded() {
        if (!isEncoding) return
        if (!rtpSender.hasDestinations() && wsManager.getServerClientCount() == 0) {
            Log.d(TAG, "No clients, stopping encoding")
            stopEncodingAndRtp()
        }
    }

    // ===== Camera =====

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            Handler(Looper.getMainLooper()).post {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                // Don't auto-bind here; binding is triggered by:
                // - setSurfaceProvider() (old mode)
                // - bindPreviewUseCase() (getSurfaceProvider mode)
                // - bindCamera() (internal/WiFi client mode)
            }
        }, cameraExecutor)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { bindCamera() }
            return
        }

        val provider = cameraProvider ?: return
        if (isCameraOpen) return

        try {
            provider.unbindAll()

            // Initialize OpenGL if not done
            if (!initializeOpenGl()) {
                Log.e(TAG, "Failed to initialize OpenGL")
                return
            }

            // When using getSurfaceProvider() mode, Activity creates its own Preview
            // and binds it to the service's lifecycle. Just bind the use case.
            // Only create a service-owned Preview if no external provider is set.
            if (!useExternalPreview && surfaceProvider == null && wrapperSurfaceProvider == null) {
                // Create service-owned Preview
                preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionFilter(object : ResolutionFilter {
                                override fun filter(
                                    supportedSizes: MutableList<android.util.Size>,
                                    rotationDegrees: Int
                                ): MutableList<android.util.Size> {
                                    val maxMegaPixels = 1920 * 1080
                                    val targetPixels = 1280 * 720
                                    val candidates = supportedSizes.filter {
                                        it.width * it.height <= maxMegaPixels
                                    }
                                    val source = if (candidates.isNotEmpty()) candidates else supportedSizes.toList()
                                    return source.sortedBy {
                                        Math.abs(it.width * it.height - targetPixels)
                                    }.toMutableList()
                                }
                            })
                            .build()
                    )
                    .build()

                val surfaceTexture = cameraSurfaceTexture
                if (surfaceTexture == null) {
                    Log.e(TAG, "No SurfaceTexture available")
                    return
                }

                preview?.setSurfaceProvider(object : Preview.SurfaceProvider {
                    override fun onSurfaceRequested(request: androidx.camera.core.SurfaceRequest) {
                        val resolution = request.resolution
                        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                        val surface = Surface(surfaceTexture)
                        request.provideSurface(surface, cameraExecutor) { result ->
                            Log.d(TAG, "Camera surface released")
                            surface.release()
                        }
                    }
                })
            } else {
                Log.d(TAG, "Skipping auto surface provider setup (using getSurfaceProvider() mode)")
            }

            // Bind to lifecycle
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )

            isCameraOpen = true
            Log.d(TAG, "Camera bound with preview (isEncoding=$isEncoding)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    private fun unbindPreview() {
        preview?.setSurfaceProvider(null)
        preview = null
    }

    private fun openCamera() {
        bindCamera()
    }

    private fun closeCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { closeCamera() }
            return
        }
        if (!isCameraOpen) return
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()
            camera = null
            preview = null
            isCameraOpen = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    /**
     * Check if camera should be closed (no preview and no encoder surface).
     */
    private fun checkAndCloseCameraIfNoSurfaces() {
        if (!hasAnySurface()) {
            Log.d(TAG, "No surfaces available, closing camera")
            closeCamera()
        }
    }

    // ===== WakeLock =====

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RCamera::RemoteCameraWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ===== Notification =====

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    private fun buildNotification(): Notification {
        val wsInfo = if (wsManager.isServerRunning()) {
            " | WS: ${wsManager.getServerUrl()} (${wsManager.getServerClientCount()}客户端)"
        } else ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_notification_title) + wsInfo)
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setOngoing(true)
            .build()
        Log.d(TAG, "Notification built: title=${getString(R.string.camera_notification_title)}$wsInfo")
        return notification
    }

    private fun updateNotification() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.d(TAG, "Notification update skipped: service is destroyed")
            return
        }
        Log.d(TAG, "Notification show/update (id=$NOTIFICATION_ID)")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
