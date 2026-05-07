package com.dragon.rcamera

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
        private const val TAG = "CameraViewerActivity"
    }

    private val wsManager = WebSocketManager()
    private val rtpReceiver = RtpReceiver()
    private val h264Decoder = H264Decoder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: run {
            finish()
            return
        }
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "远程摄像头"

        setContent {
            RCameraTheme {
                CameraViewerScreen(
                    cameraName = cameraName,
                    wsUrl = wsUrl,
                    password = password,
                    wsManager = wsManager,
                    rtpReceiver = rtpReceiver,
                    h264Decoder = h264Decoder,
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
    wsManager: WebSocketManager,
    rtpReceiver: RtpReceiver,
    h264Decoder: H264Decoder,
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
    var showErrorDialog by remember { mutableStateOf(false) }

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
                    val sent = wsManager.sendCommand(WsMessage.ACTION_START_RTP, com.google.gson.JsonObject().apply {
                        addProperty("rtp_port", localRtpPort)
                    })
                    Log.d("CameraViewer", "Sent start_rtp command: port=$localRtpPort, sent=$sent")
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
                showErrorDialog = true
            }
        }

        wsManager.onClientMessageReceived = { message ->
            Log.d("CameraViewer", "Received message: action=${message.action}, payload=${message.payload}")
            if (message.action == "start_rtp_result") {
                val success = message.payload.get("success")?.asBoolean ?: false
                if (success) {
                    isReceiving = true
                    val serverRtpPort = message.payload.get("server_rtp_port")?.asInt ?: 0
                    val w = message.payload.get("video_width")?.asInt ?: 0
                    val h = message.payload.get("video_height")?.asInt ?: 0
                    videoWidth = w
                    videoHeight = h
                    Log.d("CameraViewer", "RTP stream started, server_rtp_port=$serverRtpPort, video=${w}x${h}")
                } else {
                    Log.e("CameraViewer", "RTP start failed: ${message.payload}")
                }
            }
        }

        onDispose {
            h264Decoder.stop()
            rtpReceiver.stop()
            wsManager.stop()
        }
    }

    // Start RTP receiver immediately
    LaunchedEffect(Unit) {
        rtpReceiver.onFrameReceived = { frameData ->
            h264Decoder.feedFrame(frameData)
        }
        rtpReceiver.onKeyFrameRequested = {
            Log.d("CameraViewer", "Requesting keyframe from server")
            wsManager.sendCommand(WsMessage.ACTION_REQUEST_KEYFRAME, com.google.gson.JsonObject())
        }
        rtpReceiver.start(0) // Use any available port
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cameraName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // Video: fill height, maintain 9:16 ratio — overflow horizontally for crop-center
            AndroidView(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(9f / 16f)
                    .align(Alignment.Center),
                factory = { ctx ->
                    SurfaceView(ctx).also { surfaceView ->
                        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                holder.setFixedSize(720, 1280)
                                h264Decoder.start(holder.surface)
                                isSurfaceReady = true
                                wsManager.connectAsClient(wsUrl, password)
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

            // Status overlay — semi-transparent, does not affect video layout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Color.Black.copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when (connectionState) {
                            WsClientState.CONNECTED -> if (isReceiving) "接收视频中..." else "已连接，等待视频流..."
                            WsClientState.CONNECTING -> "连接中..."
                            WsClientState.AUTHENTICATING -> "认证中..."
                            WsClientState.DISCONNECTED -> "未连接"
                            WsClientState.AUTH_FAILED -> "认证失败"
                            WsClientState.ERROR -> "连接错误"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    if (debugInfo.isNotBlank()) {
                        Text(
                            text = debugInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // AUTH_FAILED dialog — password input for retry
            if (showAuthFailedDialog) {
                AlertDialog(
                    onDismissRequest = { showAuthFailedDialog = false },
                    title = { Text("认证失败") },
                    text = {
                        Column {
                            Text("密码错误，请重新输入密码")
                            Spacer(modifier = Modifier.height(8.dp))
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
                            wsManager.stop()
                            wsManager.connectAsClient(wsUrl, retryPassword)
                        }) {
                            Text("重试")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showAuthFailedDialog = false
                            onBack()
                        }) {
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
                            wsManager.stop()
                            wsManager.connectAsClient(wsUrl, password)
                        }) {
                            Text("重试")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showErrorDialog = false
                            onBack()
                        }) {
                            Text("返回")
                        }
                    }
                )
            }
        }
    }
}
