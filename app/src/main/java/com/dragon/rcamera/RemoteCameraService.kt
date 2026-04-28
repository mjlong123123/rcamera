package com.dragon.rcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import java.util.concurrent.Executors

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
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()
    private val wsManager = WebSocketManager()
    private var isCameraOpen = false

    // RTP and encoding
    private val rtpSender = RtpSender()
    private var encoder: MediaCodec? = null
    private var isEncoding = false
    private var imageAnalysis: ImageAnalysis? = null
    private val encodingLock = Object()
    private var encoderWidth = 1280
    private var encoderHeight = 720
    private var frameRotationDegrees = 0

    // Track client RTP ports: clientId -> rtpPort
    private val clientRtpPorts = mutableMapOf<String, Int>()
    // Track client addresses: clientId -> hostAddress
    private val clientAddresses = mutableMapOf<String, String>()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RemoteCameraService = this@RemoteCameraService
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        acquireWakeLock()
        initCamera()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START_WS -> {
                    val store = CameraStore(this)
                    val port = it.getIntExtra(EXTRA_PORT, store.getServerPort())
                    val password = it.getStringExtra(EXTRA_PASSWORD) ?: store.getServerPassword()
                    startWebSocketServer(port, password)
                }
                ACTION_STOP_WS -> {
                    stopWebSocketServer()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopWebSocketServer()
        stopEncodingAndRtp()
        unbindPreview()
        camera = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        // If currently encoding with analysis but no preview bound, rebind to add preview
        if (isEncoding && preview == null && cameraProvider != null) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { openCameraWithAnalysis() }
            } else {
                openCameraWithAnalysis()
            }
        } else {
            bindPreview()
        }
    }

    fun clearSurfaceProvider() {
        surfaceProvider = null
        unbindPreview()
    }

    // ===== WebSocket Server =====

    fun startWebSocketServer(port: Int, password: String) {
        wsManager.onServerMessageReceived = { conn, message ->
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
        wsManager.startServer(port, password)
        updateNotification()
    }

    fun stopWebSocketServer() {
        stopEncodingAndRtp()
        wsManager.stopServer()
        updateNotification()
    }

    fun isWsServerRunning(): Boolean = wsManager.isServerRunning()

    fun getWsServerUrl(): String? = wsManager.getServerUrl()

    fun getWsServerUrlForAddress(addr: String): String? = wsManager.getServerUrlForAddress(addr)

    fun getWsServerIpInfo(): com.dragon.rcamera.websocket.IpInfo? = wsManager.getServerIpInfo()

    fun getWsClientCount(): Int = wsManager.getServerClientCount()

    private fun handleClientAuthenticated(conn: WebSocket) {
        // Client just authenticated - send RTP info
        val host = conn.remoteSocketAddress?.address?.hostAddress ?: return
        val clientId = conn.remoteSocketAddress.toString()
        val isIpv6 = host.contains(":")

        clientAddresses[clientId] = host
        Log.d(TAG, "Client authenticated: $clientId from $host (IPv${if (isIpv6) "6" else "4"})")

        // If this is the first client, start encoding and RTP
        if (wsManager.getServerClientCount() == 1) {
            startEncodingAndRtp()
        }
    }

    private fun handleClientDisconnected(conn: WebSocket) {
        val clientId = conn.remoteSocketAddress.toString()

        // Remove RTP destination
        rtpSender.removeDestination(clientId)
        clientRtpPorts.remove(clientId)
        clientAddresses.remove(clientId)

        // If no clients left, stop encoding and RTP
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
                broadcastCameraStatus()
            }
            WsMessage.ACTION_CLOSE_CAMERA -> {
                closeCamera()
                val response = message.makeResponse(true, mapOf("camera_open" to false))
                wsManager.sendToClient(conn, response)
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
            }
            WsMessage.ACTION_START_RTP -> {
                // Client requests RTP stream with its receiving port
                val rtpPort = message.payload.get("rtp_port")?.asInt ?: return
                val host = conn.remoteSocketAddress?.address?.hostAddress ?: return
                val isIpv6 = host.contains(":")

                clientRtpPorts[clientId] = rtpPort
                rtpSender.addDestination(clientId, host, rtpPort)
                Log.d(TAG, "RTP destination added: $host:$rtpPort (IPv${if (isIpv6) "6" else "4"}), sender socket bound to ${rtpSender.getLocalPort()}")

                // Calculate encoded video dimensions (after rotation)
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

                // Ensure encoding is running
                if (!isEncoding) {
                    startEncodingAndRtp()
                }
            }
            WsMessage.ACTION_STOP_RTP -> {
                rtpSender.removeDestination(clientId)
                clientRtpPorts.remove(clientId)

                val response = message.makeResponse(true, mapOf("rtp_stopped" to true))
                wsManager.sendToClient(conn, response)

                // If no RTP destinations, stop encoding
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
    }

    // ===== RTP / Encoding =====

    private fun startEncodingAndRtp() {
        synchronized(encodingLock) {
            if (isEncoding) return
            try {
                rtpSender.start()
                wsManager.setRtpPort(rtpSender.getLocalPort())
                // Encoder will be created lazily when first frame arrives from ImageAnalysis,
                // so we use the actual camera resolution instead of hardcoded values.
                openCameraWithAnalysis()
                isEncoding = true
                Log.d(TAG, "Encoding and RTP started (encoder deferred to first frame)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start encoding", e)
            }
        }
    }

    private fun stopEncodingAndRtp() {
        synchronized(encodingLock) {
            if (!isEncoding) return
            isEncoding = false
            stopH264Encoder()
            rtpSender.stop()
            clientRtpPorts.clear()
            clientAddresses.clear()
            imageAnalysis = null
            // Re-open camera in preview-only mode
            openCamera()
            Log.d(TAG, "Encoding and RTP stopped, camera resumed preview")
        }
    }

    private fun startH264Encoder(width: Int, height: Int, rotationDegrees: Int) {
        try {
            // When rotation is 90 or 270, swap width/height for the encoded output
            // since we will rotate the YUV data before feeding to the encoder.
            val needSwap = rotationDegrees == 90 || rotationDegrees == 270
            val encodedWidth = if (needSwap) height else width
            val encodedHeight = if (needSwap) width else height
            encoderWidth = width
            encoderHeight = height
            frameRotationDegrees = rotationDegrees

            // Scale bitrate proportionally to pixel count (base: 2Mbps for 1280x720)
            val basePixels = 1280 * 720
            val actualPixels = encodedWidth * encodedHeight
            val bitrate = (2_000_000L * actualPixels / basePixels).toInt().coerceIn(500_000, 8_000_000)
            val fps = 30

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, encodedWidth, encodedHeight
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Use synchronous mode - input from ImageAnalysis, output from drain thread
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()

            // Start output drain thread
            drainThread = Thread({ drainEncoderLoop() }, "EncoderDrainThread").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "H264 encoder started: ${width}x${height} rot=${rotationDegrees} -> encoded ${encodedWidth}x${encodedHeight} @ ${fps}fps, bitrate=${bitrate}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264 encoder", e)
        }
    }

    private fun stopH264Encoder() {
        isEncoding = false
        try {
            drainThread?.join(1000)
        } catch (_: Exception) {}
        drainThread = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping encoder", e)
        }
        encoder = null
    }

    private var drainThread: Thread? = null

    private fun drainEncoderLoop() {
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
                        // Always call sendH264AccessUnit - it will cache config data
                        // even if there are no destinations yet
                        rtpSender.sendH264AccessUnit(data, data.size, info.presentationTimeUs, isConfig = isConfig)
                        if (isConfig) {
                            Log.d(TAG, "Encoder output SPS/PPS config: ${info.size} bytes")
                        }
                    }
                    enc.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Encoder output format changed: ${enc.outputFormat}")
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(TAG, "Encoder drain error", e)
                }
            }
        }
    }

    /**
     * Feed camera frame to encoder.
     * Called from ImageAnalysis analyzer.
     * Handles rotation by transforming YUV data when rotationDegrees is 90 or 270.
     */
    private fun feedFrameToEncoder(image: ImageProxy) {
        if (!isEncoding) return

        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees

        // Lazily create encoder on first frame using actual camera resolution and rotation
        if (encoder == null) {
            synchronized(encodingLock) {
                if (encoder == null) {
                    startH264Encoder(width, height, rotation)
                }
            }
        }

        val enc = encoder ?: return

        // Dimensions must match encoder configuration
        if (width != encoderWidth || height != encoderHeight) {
            Log.w(TAG, "Image dimensions ($width x $height) don't match encoder ($encoderWidth x $encoderHeight), skipping frame")
            return
        }

        try {
            val index = enc.dequeueInputBuffer(0)
            if (index < 0) return // No input buffer available

            val inputBuffer = enc.getInputBuffer(index) ?: return
            inputBuffer.clear()

            // Convert YUV_420_888 to NV12 byte array
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            if (rotation == 90 || rotation == 270) {
                // Need to rotate the frame. First extract Y/U/V into compact arrays,
                // then rotate and write to input buffer in NV12 format.
                val rotatedWidth = height  // After 90/270 rotation
                val rotatedHeight = width

                // Extract compact Y plane
                val yData = ByteArray(width * height)
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(yData, row * width, width)
                }

                // Extract compact U and V planes
                val uvHeight = height / 2
                val uvWidth = width / 2
                val uData = ByteArray(uvWidth * uvHeight)
                val vData = ByteArray(uvWidth * uvHeight)
                if (uvPixelStride == 1) {
                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            val idx = row * uvWidth + col
                            uData[idx] = uBuffer.get(row * uvRowStride + col)
                            vData[idx] = vBuffer.get(row * uvRowStride + col)
                        }
                    }
                } else {
                    for (row in 0 until uvHeight) {
                        for (col in 0 until uvWidth) {
                            val idx = row * uvWidth + col
                            uData[idx] = uBuffer.get(row * uvRowStride + col * uvPixelStride)
                            vData[idx] = vBuffer.get(row * uvRowStride + col * uvPixelStride)
                        }
                    }
                }

                // Rotate Y plane
                val rotatedY = ByteArray(rotatedWidth * rotatedHeight)
                val rotatedU = ByteArray(uvHeight * uvWidth)
                val rotatedV = ByteArray(uvHeight * uvWidth)

                if (rotation == 90) {
                    // 90° CW: (x, y) -> (height - 1 - y, x)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val srcIdx = y * width + x
                            val dstX = height - 1 - y
                            val dstY = x
                            rotatedY[dstY * rotatedWidth + dstX] = yData[srcIdx]
                        }
                    }
                    // Rotate UV planes (half resolution)
                    for (y in 0 until uvHeight) {
                        for (x in 0 until uvWidth) {
                            val srcIdx = y * uvWidth + x
                            val dstX = uvHeight - 1 - y
                            val dstY = x
                            rotatedU[dstY * uvHeight + dstX] = uData[srcIdx]
                            rotatedV[dstY * uvHeight + dstX] = vData[srcIdx]
                        }
                    }
                } else {
                    // 270° CW (or 90° CCW): (x, y) -> (y, width - 1 - x)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val srcIdx = y * width + x
                            val dstX = y
                            val dstY = width - 1 - x
                            rotatedY[dstY * rotatedWidth + dstX] = yData[srcIdx]
                        }
                    }
                    for (y in 0 until uvHeight) {
                        for (x in 0 until uvWidth) {
                            val srcIdx = y * uvWidth + x
                            val dstX = y
                            val dstY = uvWidth - 1 - x
                            rotatedU[dstY * uvHeight + dstX] = uData[srcIdx]
                            rotatedV[dstY * uvHeight + dstX] = vData[srcIdx]
                        }
                    }
                }

                // Write rotated Y
                inputBuffer.put(rotatedY)

                // Write rotated UV interleaved (NV12 format)
                val rotatedUVCount = uvHeight * uvWidth
                for (i in 0 until rotatedUVCount) {
                    inputBuffer.put(rotatedU[i])
                    inputBuffer.put(rotatedV[i])
                }
            } else {
                // No rotation needed (0° or 180°) - use original logic
                // Y plane - copy row by row to handle stride padding
                val yRow = ByteArray(width)
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(yRow, 0, width)
                    inputBuffer.put(yRow)
                }

                // UV plane - NV12 format: interleaved UV (NOT VU)
                if (uvPixelStride == 1) {
                    for (row in 0 until height / 2) {
                        for (col in 0 until width / 2) {
                            val uvIndex = row * uvRowStride + col
                            inputBuffer.put(uBuffer.get(uvIndex))
                            inputBuffer.put(vBuffer.get(uvIndex))
                        }
                    }
                } else {
                    for (row in 0 until height / 2) {
                        for (col in 0 until width / 2) {
                            val uvIndex = row * uvRowStride + col * uvPixelStride
                            inputBuffer.put(uBuffer.get(uvIndex))
                            inputBuffer.put(vBuffer.get(uvIndex))
                        }
                    }
                }
            }

            enc.queueInputBuffer(
                index,
                0,
                inputBuffer.position(),
                System.nanoTime() / 1000,
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error feeding frame to encoder", e)
        }
    }

    // ===== Camera =====

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            Handler(Looper.getMainLooper()).post {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                openCamera()
            }
        }, cameraExecutor)
    }

    private fun openCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { openCamera() }
            return
        }
        if (isCameraOpen) return
        val provider = cameraProvider ?: return
        val sp = surfaceProvider
        try {
            if (sp != null) {
                preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(sp)
                }
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } else {
                // No surface provider available; defer camera binding until surface is ready.
                // The camera will be bound in setSurfaceProvider() when it becomes available.
                preview = null
                Log.d(TAG, "No surface provider, deferring camera binding")
                return
            }
            isCameraOpen = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            e.printStackTrace()
        }
    }

    private fun bindPreview() {
        val p = preview ?: return
        surfaceProvider?.let { provider -> p.setSurfaceProvider(provider) }
    }

    private fun unbindPreview() {
        preview?.setSurfaceProvider(null)
    }

    /**
     * Start camera with image analysis for encoding.
     * This is separate from the preview-only camera.
     */
    private fun openCameraWithAnalysis() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { openCameraWithAnalysis() }
            return
        }
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionFilter(object : ResolutionFilter {
                            override fun filter(
                                supportedSizes: MutableList<android.util.Size>,
                                rotationDegrees: Int
                            ): MutableList<android.util.Size> {
                                // CameraX returns raw sensor sizes (always landscape-oriented on phones).
                                // After rotation in feedFrameToEncoder, the output will be portrait.
                                // Prefer sizes that produce ~720p after rotation (e.g. 1280x720 -> 720x1280).
                                // Cap at 1080p to limit bandwidth and CPU usage.
                                val maxMegaPixels = 1920 * 1080
                                val targetPixels = 1280 * 720 // target raw resolution before rotation
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        if (isEncoding) {
                            feedFrameToEncoder(image)
                        }
                        image.close()
                    }
                }

            // Only bind Preview if we have a surface provider; otherwise CameraX will fail
            // with "Unable to open capture session without surfaces".
            // ImageAnalysis alone is sufficient for RTP encoding.
            val sp = surfaceProvider
            if (sp != null) {
                preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(sp)
                }
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } else {
                preview = null
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
            }
            isCameraOpen = true
            Log.d(TAG, "Camera opened with image analysis (preview=${sp != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera with analysis", e)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val wsInfo = if (wsManager.isServerRunning()) {
            " | WS: ${wsManager.getServerUrl()} (${wsManager.getServerClientCount()}客户端)"
        } else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_notification_title) + wsInfo)
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
