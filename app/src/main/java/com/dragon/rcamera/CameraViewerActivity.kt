package com.dragon.rcamera

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

    // Update debug info periodically
    LaunchedEffect(rtpReceiver, h264Decoder) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            debugInfo = "RTP: ${rtpReceiver.getStats()} | Dec: ${h264Decoder.getStats()}"
        }
    }

    // Setup WebSocket message handler
    DisposableEffect(wsManager) {
        wsManager.onClientStateChanged = { state ->
            connectionState = state
            if (state == WsClientState.CONNECTED) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Status bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column {
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
                        color = when (connectionState) {
                            WsClientState.CONNECTED -> MaterialTheme.colorScheme.primary
                            WsClientState.CONNECTING, WsClientState.AUTHENTICATING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    if (debugInfo.isNotBlank()) {
                        Text(
                            text = debugInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Video surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).also { surfaceView ->
                            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    h264Decoder.start(holder.surface)
                                    isSurfaceReady = true
                                    // Now connect WebSocket to start the auth + RTP flow
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
            }
        }
    }
}
