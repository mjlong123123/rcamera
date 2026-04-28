package com.dragon.rcamera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dragon.rcamera.data.CameraStore
import com.dragon.rcamera.ui.theme.RCameraTheme
import com.dragon.rcamera.websocket.IpInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class CameraActivity : ComponentActivity() {

    private var cameraService: RemoteCameraService? = null
    private var isBound = false
    private var hasPermission = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RemoteCameraService.LocalBinder
            cameraService = binder.getService()
            isBound = true
            showCameraPreview()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
            startAndBindService()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
            startAndBindService()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        showWaitingScreen()
    }

    private fun showWaitingScreen() {
        setContent {
            RCameraTheme {
                WaitingScreen()
            }
        }
    }

    private fun showCameraPreview() {
        setContent {
            RCameraTheme {
                CameraPreviewScreen(
                    cameraService = cameraService,
                    onSurfaceProviderReady = { previewView ->
                        cameraService?.setSurfaceProvider(previewView.surfaceProvider)
                    },
                    onSurfaceDestroyed = {
                        cameraService?.clearSurfaceProvider()
                    }
                )
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, RemoteCameraService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // 自动启动 WebSocket 服务器
        val store = CameraStore(this)
        val wsIntent = Intent(this, RemoteCameraService::class.java).apply {
            action = RemoteCameraService.ACTION_START_WS
            putExtra(RemoteCameraService.EXTRA_PORT, store.getServerPort())
            putExtra(RemoteCameraService.EXTRA_PASSWORD, store.getServerPassword())
        }
        startService(wsIntent)
    }

    override fun onDestroy() {
        cameraService?.clearSurfaceProvider()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        // 停止 WebSocket 服务器
        val wsIntent = Intent(this, RemoteCameraService::class.java).apply {
            action = RemoteCameraService.ACTION_STOP_WS
        }
        startService(wsIntent)
        stopService(Intent(this, RemoteCameraService::class.java))
        super.onDestroy()
    }
}

@Composable
fun WaitingScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "正在连接相机服务...",
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    cameraService: RemoteCameraService?,
    onSurfaceProviderReady: (PreviewView) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { CameraStore(context) }
    var isWsRunning by remember { mutableStateOf(cameraService?.isWsServerRunning() ?: false) }
    var wsUrl by remember { mutableStateOf(cameraService?.getWsServerUrl() ?: "") }
    var ipInfo by remember { mutableStateOf<IpInfo?>(cameraService?.getWsServerIpInfo()) }
    var clientCount by remember { mutableStateOf(cameraService?.getWsClientCount() ?: 0) }
    var showSettings by remember { mutableStateOf(false) }
    var serverPassword by remember { mutableStateOf(store.getServerPassword()) }
    var serverPort by remember { mutableStateOf(store.getServerPort().toString()) }

    // 定期刷新状态
    LaunchedEffect(cameraService) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            isWsRunning = cameraService?.isWsServerRunning() ?: false
            wsUrl = cameraService?.getWsServerUrl() ?: ""
            ipInfo = cameraService?.getWsServerIpInfo()
            clientCount = cameraService?.getWsClientCount() ?: 0
        }
    }

    // 局域网地址提示
    LaunchedEffect(ipInfo) {
        val info = ipInfo ?: return@LaunchedEffect
        if (info.isLan && isWsRunning) {
            Toast.makeText(
                context,
                "当前使用局域网地址，只能在同一局域网中连接",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 设置对话框
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("服务器设置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverPassword,
                        onValueChange = { serverPassword = it },
                        label = { Text("连接密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
                        label = { Text("端口号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.setServerPassword(serverPassword)
                    val port = serverPort.toIntOrNull() ?: 8888
                    store.setServerPort(port)
                    // 重启 WebSocket 服务器
                    cameraService?.stopWebSocketServer()
                    cameraService?.startWebSocketServer(port, serverPassword)
                    showSettings = false
                }) {
                    Text("保存并重启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相机") },
                navigationIcon = {
                    IconButton(onClick = {
                        (context as? ComponentActivity)?.finish()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            // WebSocket 服务器状态卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isWsRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isWsRunning) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (isWsRunning) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isWsRunning) "WebSocket 服务器运行中" else "WebSocket 服务器未运行",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    if (isWsRunning && ipInfo != null) {
                        val info = ipInfo!!

                        Spacer(modifier = Modifier.height(8.dp))

                        // Display address based on availability: IPv6 preferred, IPv4 only when no IPv6
                        if (info.ipv6Address != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "IPv6: ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "[${info.ipv6Address}]:${serverPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (info.isIpv6Lan) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "局域网",
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFFFFC107)
                                    )
                                }
                            }
                        } else if (info.ipv4Address != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "IPv4: ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${info.ipv4Address}:${serverPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // LAN warning
                        if (info.isLan) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFFFFC107)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "局域网地址，仅限局域网内连接",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFFC107)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已连接客户端: $clientCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // 二维码 - use preferred address
                        val qrContent = wsUrl
                        if (qrContent.isNotBlank()) {
                            val qrBitmap = remember(qrContent) {
                                generateQrBitmap(qrContent, 100)
                            }
                            qrBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "二维码",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .aspectRatio(1f)
                                        .align(Alignment.CenterHorizontally)
                                )
                            }
                            Text(
                                text = "扫描二维码添加此摄像头",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }

            // 相机预览
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            onSurfaceProviderReady(previewView)
                        }
                    },
                    onRelease = {
                        onSurfaceDestroyed()
                    }
                )
            }
        }
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
