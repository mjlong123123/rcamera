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
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    private var preview: Preview? = null

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
                    onCreatePreview = { svc ->
                        createAndBindPreview(svc)
                    },
                    onPreviewSurfaceReady = { surface ->
                        cameraService?.setPreviewSurface(surface)
                    },
                    onPreviewSurfaceDestroyed = {
                        cameraService?.setPreviewSurface(null)
                    },
                    onSurfaceDestroyed = {
                        preview?.setSurfaceProvider(null)
                        preview = null
                        cameraService?.clearSurfaceProvider()
                    }
                )
            }
        }
    }

    private fun createAndBindPreview(service: RemoteCameraService) {
        preview?.setSurfaceProvider(null)
        preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionFilter(ResolutionFilter { supportedSizes, _ ->
                        val maxMegaPixels = 1920 * 1080
                        val targetPixels = 1280 * 720
                        val candidates = supportedSizes.filter {
                            it.width * it.height <= maxMegaPixels
                        }
                        val source = if (candidates.isNotEmpty()) candidates else supportedSizes.toList()
                        source.sortedBy {
                            Math.abs(it.width * it.height - targetPixels)
                        }.toMutableList()
                    })
                    .build()
            )
            .build().also { p ->
                service.getSurfaceProvider()?.let { provider ->
                    p.setSurfaceProvider(provider)
                }
                service.bindPreviewUseCase(p)
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
    onCreatePreview: (RemoteCameraService) -> Unit,
    onPreviewSurfaceReady: (Surface) -> Unit,
    onPreviewSurfaceDestroyed: () -> Unit,
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
    var selectedQrAddress by remember { mutableStateOf<String?>(null) }
    var isFrontCamera by remember { mutableStateOf(cameraService?.isFrontCamera() ?: false) }

    // Bind preview on first composition
    LaunchedEffect(cameraService) {
        cameraService?.let { onCreatePreview(it) }
    }

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
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "连接信息",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .imePadding()
                    ) {
                    // 连接信息
                    if (isWsRunning && ipInfo != null) {
                        val info = ipInfo!!
                        val port = serverPort.toIntOrNull() ?: 8888

                        Text(
                            text = "选择连接地址",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // IPv6 address row
                        if (info.ipv6Address != null) {
                            val isIpv6Selected = selectedQrAddress == info.ipv6Address ||
                                (selectedQrAddress == null && info.preferredAddress == info.ipv6Address)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedQrAddress = info.ipv6Address },
                                shape = MaterialTheme.shapes.small,
                                color = if (isIpv6Selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    if (isIpv6Selected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "已选择",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "IPv6",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "[${info.ipv6Address}]:$port",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            softWrap = true
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // IPv4 address row
                        if (info.ipv4Address != null) {
                            val isIpv4Selected = selectedQrAddress == info.ipv4Address ||
                                (selectedQrAddress == null && info.preferredAddress == info.ipv4Address)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedQrAddress = info.ipv4Address },
                                shape = MaterialTheme.shapes.small,
                                color = if (isIpv4Selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    if (isIpv4Selected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "已选择",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "IPv4",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${info.ipv4Address}:$port",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Connection type hint based on selected address
                        val selectedAddr = selectedQrAddress ?: info.preferredAddress
                        val isIpv6 = selectedAddr == info.ipv6Address
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = if (isIpv6)
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isIpv6) Icons.Default.Check else Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isIpv6) Color(0xFF4CAF50) else Color(0xFFFFC107)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isIpv6)
                                        "IPv6 地址支持外网连接，远程设备可直接访问"
                                    else
                                        "IPv4 地址仅限局域网内连接，外网无法直接访问",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isIpv6)
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        // QR Code with rcamera:// scheme
                        Spacer(modifier = Modifier.height(16.dp))
                        val qrAddress = selectedQrAddress ?: info.preferredAddress
                        val wsUrlForQr = cameraService?.getWsServerUrlForAddress(qrAddress) ?: wsUrl
                        if (wsUrlForQr.isNotBlank()) {
                            // Generate rcamera:// scheme URL
                            val schemeUrl = buildString {
                                append("rcamera://add?wsUrl=")
                                append(android.net.Uri.encode(wsUrlForQr))
                                append("&port=")
                                append(port)
                                append("&password=")
                                append(android.net.Uri.encode(serverPassword))
                            }
                            val qrBitmap = remember(schemeUrl) {
                                generateQrBitmap(schemeUrl, 256)
                            }

                            Text(
                                text = "扫码添加摄像头",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "使用另一台手机扫描下方二维码，即可添加此摄像头",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            qrBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "二维码",
                                    modifier = Modifier
                                        .size(180.dp)
                                        .aspectRatio(1f)
                                        .align(Alignment.CenterHorizontally)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击上方地址可切换 IPv4/IPv6",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 服务器配置
                    Text(
                        text = "服务器配置",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showSettings = false }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
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
                    }
                }
            }
        }
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
                    IconButton(onClick = {
                        cameraService?.let { svc ->
                            if (svc.switchCamera()) {
                                isFrontCamera = svc.isFrontCamera()
                                onCreatePreview(svc)
                            } else {
                                Toast.makeText(context, "无法切换摄像头", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = "切换摄像头",
                            tint = if (isFrontCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "二维码")
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
            // 简洁的服务器状态栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isWsRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isWsRunning) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isWsRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isWsRunning) "服务器运行中" else "服务器未运行",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isFrontCamera) "前置" else "后置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isWsRunning) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "$clientCount 个客户端已连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 相机预览 - TextureView 提供 Surface 给 Service 的 OpenGL 管线渲染
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier
                        .aspectRatio(9f / 16f)
                        .fillMaxSize(),
                    factory = { ctx ->
                        android.view.TextureView(ctx).also { textureView ->
                            textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    onPreviewSurfaceReady(Surface(surfaceTexture))
                                }

                                override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                                    onPreviewSurfaceDestroyed()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
                            }
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
