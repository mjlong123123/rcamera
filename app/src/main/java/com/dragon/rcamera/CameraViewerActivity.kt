package com.dragon.rcamera

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dragon.rcamera.data.CameraStore
import com.dragon.rcamera.data.RemoteCamera
import com.dragon.rcamera.rtp.AudioPlayback
import com.dragon.rcamera.rtp.H264Decoder
import com.dragon.rcamera.rtp.RtpReceiver
import com.dragon.rcamera.ui.theme.RCameraTheme
import com.dragon.rcamera.websocket.WsClientState
import com.dragon.rcamera.websocket.WsMessage
import com.dragon.rcamera.websocket.WebSocketManager

class CameraViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
        const val EXTRA_IPV4_ADDRESSES = "extra_ipv4_addresses"
        const val EXTRA_IPV6_ADDRESSES = "extra_ipv6_addresses"
        const val EXTRA_PORT = "extra_port"
        private const val TAG = "CameraViewerActivity"
    }

    private val wsManager = WebSocketManager()
    private val rtpReceiver = RtpReceiver()
    private val h264Decoder = H264Decoder()
    private val audioPlayback = AudioPlayback()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: run {
            finish()
            return
        }
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "远程摄像头"
        val ipv4Addresses = intent.getStringArrayListExtra(EXTRA_IPV4_ADDRESSES)?.toList() ?: emptyList()
        val ipv6Addresses = intent.getStringArrayListExtra(EXTRA_IPV6_ADDRESSES)?.toList() ?: emptyList()
        val port = intent.getIntExtra(EXTRA_PORT, 8888)

        setContent {
            RCameraTheme {
                CameraViewerScreen(
                    cameraName = cameraName,
                    wsUrl = wsUrl,
                    password = password,
                    ipv4Addresses = ipv4Addresses,
                    ipv6Addresses = ipv6Addresses,
                    port = port,
                    wsManager = wsManager,
                    rtpReceiver = rtpReceiver,
                    h264Decoder = h264Decoder,
                    audioPlayback = audioPlayback,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        audioPlayback.stop()
        h264Decoder.stop()
        rtpReceiver.stop()
        wsManager.stop()
        Log.d(TAG, "All connections closed")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraViewerScreen(
    cameraName: String,
    wsUrl: String,
    password: String,
    ipv4Addresses: List<String> = emptyList(),
    ipv6Addresses: List<String> = emptyList(),
    port: Int = 8888,
    wsManager: WebSocketManager,
    rtpReceiver: RtpReceiver,
    h264Decoder: H264Decoder,
    audioPlayback: AudioPlayback,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var connectionState by remember { mutableStateOf(WsClientState.DISCONNECTED) }
    var isReceiving by remember { mutableStateOf(false) }
    var isSurfaceReady by remember { mutableStateOf(false) }
    var debugInfo by remember { mutableStateOf("") }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var networkSpeed by remember { mutableStateOf("") }
    var hasBeenConnected by remember { mutableStateOf(false) }
    var showAuthFailedDialog by remember { mutableStateOf(false) }
    var retryPassword by remember { mutableStateOf(password) }
    var passwordChanged by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }

    // Sequential connection state
    val connectionAttemptUrls = remember {
        buildList {
            // IPv6 first (external connection), then IPv4 (LAN fallback)
            ipv6Addresses.forEach { add("ws://[$it]:$port") }
            ipv4Addresses.forEach { add("ws://$it:$port") }
            if (isEmpty()) add(wsUrl)
        }
    }
    var currentAttemptUrl by remember { mutableStateOf(connectionAttemptUrls.firstOrNull() ?: wsUrl) }
    var currentAttemptIndex by remember { mutableStateOf(0) }
    var isRetrying by remember { mutableStateOf(false) }

    // Retry effect: try next URL on connection failure
    LaunchedEffect(isRetrying) {
        if (isRetrying) {
            val nextIndex = currentAttemptIndex + 1
            if (nextIndex < connectionAttemptUrls.size) {
                currentAttemptIndex = nextIndex
                currentAttemptUrl = connectionAttemptUrls[nextIndex]
                wsManager.stop()
                kotlinx.coroutines.delay(100)
                wsManager.connectAsClient(currentAttemptUrl, password)
                isRetrying = false
            } else {
                isRetrying = false
                showErrorDialog = true
            }
        }
    }

    // Save new password to CameraStore when reconnection succeeds with a different password
    LaunchedEffect(connectionState, passwordChanged) {
        if (connectionState == WsClientState.CONNECTED && passwordChanged && retryPassword != password) {
            val store = CameraStore(context)
            val cameras = store.getCameras()
            val camera = cameras.find { it.wsUrl == wsUrl }
            if (camera != null && camera.password != retryPassword) {
                store.updateCamera(camera.copy(password = retryPassword))
            }
            passwordChanged = false
        }
    }

    // Update debug info periodically + calculate network speed
    LaunchedEffect(rtpReceiver, h264Decoder) {
        var lastBytes = 0L
        while (true) {
            kotlinx.coroutines.delay(1000)
            val currentBytes = rtpReceiver.getReceivedBytes()
            val bytesPerSec = currentBytes - lastBytes
            lastBytes = currentBytes
            val kbps = bytesPerSec * 8 / 1000
            networkSpeed = if (kbps > 1000) {
                "%.1f Mbps".format(kbps / 1000.0)
            } else {
                "$kbps Kbps"
            }
            val resInfo = if (videoWidth > 0) "${videoWidth}x${videoHeight}" else "?x?"
            debugInfo = "$resInfo | $networkSpeed | RTP: ${rtpReceiver.getStats()} | Dec: ${h264Decoder.getStats()}"
        }
    }

    // Auto-exit when server disconnects after successful connection
    LaunchedEffect(connectionState, hasBeenConnected) {
        if (connectionState == WsClientState.DISCONNECTED && hasBeenConnected) {
            kotlinx.coroutines.delay(800)
            onBack()
        }
    }

    // Setup WebSocket message handler
    DisposableEffect(wsManager) {
        wsManager.onClientStateChanged = { state ->
            connectionState = state
            if (state == WsClientState.CONNECTED) {
                hasBeenConnected = true
                // Connected and authenticated, request RTP start
                val localRtpPort = rtpReceiver.getLocalPort()
                Log.d("CameraViewer", "WebSocket connected, local RTP port=$localRtpPort")
                if (localRtpPort > 0) {
                    val localAudioPort = audioPlayback.getLocalPort()
                    val sent = wsManager.sendCommand(WsMessage.ACTION_START_RTP, com.google.gson.JsonObject().apply {
                        addProperty("rtp_port", localRtpPort)
                        if (localAudioPort > 0) {
                            addProperty("audio_port", localAudioPort)
                        }
                    })
                    Log.d("CameraViewer", "Sent start_rtp command: video_port=$localRtpPort, audio_port=$localAudioPort, sent=$sent")
                } else {
                    Log.e("CameraViewer", "RTP receiver port is 0, cannot start RTP! Receiver may have failed to start.")
                }
            }
            if (state == WsClientState.DISCONNECTED || state == WsClientState.ERROR || state == WsClientState.AUTH_FAILED) {
                isReceiving = false
            }
            if (state == WsClientState.AUTH_FAILED) {
                showAuthFailedDialog = true
            }
            if (state == WsClientState.ERROR) {
                // Before first successful connection, try next URL sequentially
                if (!hasBeenConnected && connectionAttemptUrls.size > 1) {
                    isRetrying = true
                } else {
                    showErrorDialog = true
                }
            }
        }

        wsManager.onClientMessageReceived = { message ->
            Log.d("CameraViewer", "Received message: action=${message.action}, payload=${message.payload}")
            if (message.action == "start_rtp_result") {
                val success = message.payload.get("success")?.asBoolean ?: false
                if (success) {
                    isReceiving = true
                    val serverRtpPort = message.payload.get("server_rtp_port")?.asInt ?: 0
                    val serverAudioPort = message.payload.get("server_audio_port")?.asInt ?: 0
                    val w = message.payload.get("video_width")?.asInt ?: 0
                    val h = message.payload.get("video_height")?.asInt ?: 0
                    videoWidth = w
                    videoHeight = h
                    Log.d("CameraViewer", "RTP stream started, server_rtp_port=$serverRtpPort, server_audio_port=$serverAudioPort, video=${w}x${h}")
                } else {
                    Log.e("CameraViewer", "RTP start failed: ${message.payload}")
                }
            }
            if (message.action == WsMessage.ACTION_AUDIO_CSD) {
                val csdB64 = message.payload.get("csd")?.asString
                if (csdB64 != null) {
                    val csdBytes = android.util.Base64.decode(csdB64, android.util.Base64.NO_WRAP)
                    audioPlayback.setCodecSpecificData(csdBytes)
                    Log.d("CameraViewer", "Received audio CSD (${csdBytes.size} bytes)")
                }
            }
        }

        h264Decoder.onOutputFormatChanged = { w, h ->
            Log.d("CameraViewer", "Decoder output format changed: ${w}x${h}")
            videoWidth = w
            videoHeight = h
        }

        onDispose {
            h264Decoder.onOutputFormatChanged = null
            audioPlayback.stop()
            h264Decoder.stop()
            rtpReceiver.stop()
            wsManager.stop()
        }
    }

    // Start RTP receiver and audio playback immediately
    LaunchedEffect(Unit) {
        rtpReceiver.onFrameReceived = { frameData ->
            h264Decoder.feedFrame(frameData)
        }
        rtpReceiver.onKeyFrameRequested = {
            Log.d("CameraViewer", "Requesting keyframe from server")
            wsManager.sendCommand(WsMessage.ACTION_REQUEST_KEYFRAME, com.google.gson.JsonObject())
        }
        rtpReceiver.start(0) // Use any available port

        // Start audio playback on a separate port
        audioPlayback.start()
        Log.d("CameraViewer", "AudioPlayback started on port ${audioPlayback.getLocalPort()}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cameraName)
                        if (connectionState == WsClientState.CONNECTED && isReceiving) {
                            Text(
                                text = networkSpeed,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            wsManager.sendCommand(WsMessage.ACTION_SWITCH_CAMERA, com.google.gson.JsonObject())
                        },
                        enabled = connectionState == WsClientState.CONNECTED
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = "切换摄像头",
                            tint = if (connectionState == WsClientState.CONNECTED)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clipToBounds()
        ) {
            // Video: fit within available space at the video's actual aspect ratio — no clipping
            val videoAspect = remember(videoWidth, videoHeight) {
                if (videoWidth > 0 && videoHeight > 0) {
                    videoWidth.toFloat() / videoHeight.toFloat()
                } else {
                    9f / 16f // default portrait before dimensions are known
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(videoAspect)
                    .align(Alignment.Center),
                factory = { ctx ->
                    SurfaceView(ctx).also { surfaceView ->
                        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                // Don't setFixedSize — let the Surface buffer follow the layout dimensions
                                h264Decoder.start(holder.surface)
                                isSurfaceReady = true
                                wsManager.connectAsClient(currentAttemptUrl, password)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                h264Decoder.stop()
                                isSurfaceReady = false
                            }
                        })
                    }
                }
            )

            // Connection status overlay — top of video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                when (connectionState) {
                    WsClientState.CONNECTED -> {
                        if (isReceiving) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF4CAF50).copy(alpha = 0.85f),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color.White, RoundedCornerShape(3.dp))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "接收中",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.5f),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "已连接，等待视频流...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                    WsClientState.CONNECTING, WsClientState.AUTHENTICATING -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (connectionState == WsClientState.CONNECTING) "连接中..." else "认证中...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    WsClientState.DISCONNECTED -> {
                        if (!hasBeenConnected) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.5f),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "未连接",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }

                // Debug info overlay — bottom-right
//                if (debugInfo.isNotBlank() && isReceiving) {
//                    Surface(
//                        shape = RoundedCornerShape(8.dp),
//                        color = Color.Black.copy(alpha = 0.45f),
//                        modifier = Modifier.align(Alignment.BottomEnd)
//                    ) {
//                        Text(
//                            text = debugInfo,
//                            style = MaterialTheme.typography.labelSmall,
//                            color = Color.White.copy(alpha = 0.8f),
//                            fontFamily = FontFamily.Monospace,
//                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
//                        )
//                    }
//                }
            }

            // AUTH_FAILED dialog — password input for retry
            if (showAuthFailedDialog) {
                AlertDialog(
                    onDismissRequest = { showAuthFailedDialog = false },
                    title = { Text("认证失败") },
                    text = {
                        Column {
                            Text("密码错误，请重新输入密码")
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = retryPassword,
                                onValueChange = { retryPassword = it },
                                label = { Text("连接密码") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAuthFailedDialog = false
                            hasBeenConnected = false
                            passwordChanged = true
                            currentAttemptIndex = 0
                            currentAttemptUrl = connectionAttemptUrls.firstOrNull() ?: wsUrl
                            wsManager.stop()
                            wsManager.connectAsClient(currentAttemptUrl, retryPassword)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重试")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showAuthFailedDialog = false
                            onBack()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("返回")
                        }
                    }
                )
            }

            // ERROR dialog — connection failure with retry
            if (showErrorDialog) {
                AlertDialog(
                    onDismissRequest = { showErrorDialog = false },
                    title = { Text("连接失败") },
                    text = { Text("无法连接到服务器，请检查网络连接或服务器地址") },
                    confirmButton = {
                        TextButton(onClick = {
                            showErrorDialog = false
                            hasBeenConnected = false
                            currentAttemptIndex = 0
                            currentAttemptUrl = connectionAttemptUrls.firstOrNull() ?: wsUrl
                            wsManager.stop()
                            wsManager.connectAsClient(currentAttemptUrl, password)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重试")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showErrorDialog = false
                            onBack()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("返回")
                        }
                    }
                )
            }
        }
    }
}
