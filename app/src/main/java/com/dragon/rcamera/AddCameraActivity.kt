package com.dragon.rcamera

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dragon.rcamera.data.CameraStore
import com.dragon.rcamera.data.RemoteCamera
import com.dragon.rcamera.ui.theme.RCameraTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class AddCameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()

        // Read pre-filled data from extras
        val prefillWsUrl = intent?.getStringExtra(EXTRA_WS_URL)
        val prefillPassword = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        setContent {
            RCameraTheme {
                AddCameraScreen(
                    onBack = { finish() },
                    onSaved = { wsUrl, password, name ->
                        val intent = Intent(this, CameraViewerActivity::class.java).apply {
                            putExtra(CameraViewerActivity.EXTRA_WS_URL, wsUrl)
                            putExtra(CameraViewerActivity.EXTRA_PASSWORD, password)
                            putExtra(CameraViewerActivity.EXTRA_CAMERA_NAME, name)
                        }
                        startActivity(intent)
                        finish()
                    },
                    prefillWsUrl = prefillWsUrl,
                    prefillPassword = prefillPassword
                )
            }
        }
    }

    companion object {
        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_PASSWORD = "extra_password"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCameraScreen(
    onBack: () -> Unit,
    onSaved: (wsUrl: String, password: String, name: String) -> Unit,
    prefillWsUrl: String? = null,
    prefillPassword: String? = null
) {
    val context = LocalContext.current
    val cameraStore = remember { CameraStore(context) }
    var name by remember { mutableStateOf("") }
    var wsUrl by remember { mutableStateOf(prefillWsUrl ?: "") }
    var password by remember { mutableStateOf(prefillPassword ?: "") }
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    // Auto-derive name from wsUrl if not set
    LaunchedEffect(wsUrl) {
        if (name.isBlank() && wsUrl.isNotBlank()) {
            name = "远程摄像头"
        }
    }

    // 权限请求
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isScanning = true
        } else {
            scanError = "需要相机权限才能扫描二维码"
        }
    }

    fun startScanning() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            isScanning = true
            scanError = null
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun parseQrContent(content: String): Triple<String, String, String>? {
        // 支持格式:
        // 1. rcamera://add?wsUrl=ws://[IPv6]:PORT&port=PORT&password=xxx
        // 2. rcamera://ws://IP:PORT?name=MyCamera
        // 3. ws://IP:PORT
        // 4. ws://IP:PORT?name=MyCamera
        return try {
            if (content.startsWith("rcamera://add?") || content.startsWith("rcamera://add/")) {
                val uri = Uri.parse(content)
                val wsUrlParam = uri.getQueryParameter("wsUrl") ?: return null
                val nameParam = uri.getQueryParameter("name") ?: "远程摄像头"
                val passwordParam = uri.getQueryParameter("password") ?: ""
                Triple(nameParam, wsUrlParam, passwordParam)
            } else if (content.startsWith("rcamera://")) {
                val rest = content.removePrefix("rcamera://")
                val uri = Uri.parse(rest)
                val url = rest.substringBefore("?")
                val n = uri.getQueryParameter("name") ?: "远程摄像头"
                Triple(n, url, "")
            } else if (content.startsWith("ws://") || content.startsWith("wss://")) {
                val uri = Uri.parse(content)
                val url = content.substringBefore("?")
                val n = uri.getQueryParameter("name") ?: "远程摄像头"
                Triple(n, url, "")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCamera() {
        if (name.isBlank() || wsUrl.isBlank()) return
        val camera = RemoteCamera(
            name = name.trim(),
            wsUrl = wsUrl.trim(),
            password = password.trim()
        )
        cameraStore.addCamera(camera)
        onSaved(camera.wsUrl, camera.password, camera.name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加远程摄像头") },
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
                .padding(16.dp)
        ) {
            // 二维码扫描区域
            if (isScanning) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    QrScannerView(
                        onQrCodeScanned = { content ->
                            val parsed = parseQrContent(content)
                            if (parsed != null) {
                                name = parsed.first
                                wsUrl = parsed.second
                                password = parsed.third
                                isScanning = false
                            }
                        },
                        onError = { error ->
                            scanError = error
                            isScanning = false
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { isScanning = false }) {
                    Text("关闭扫描")
                }
            } else {
                OutlinedButton(
                    onClick = { startScanning() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("扫描二维码添加")
                }
            }

            scanError?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 手动输入
            Text(
                "手动输入",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = wsUrl,
                onValueChange = { wsUrl = it },
                label = { Text("WebSocket 地址 (ws://IP:PORT)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("连接密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                onClick = { saveCamera() },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && wsUrl.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                color = if (name.isNotBlank() && wsUrl.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(
                    modifier = Modifier.padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "保存",
                        color = if (name.isNotBlank() && wsUrl.isNotBlank())
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun QrScannerView(
    onQrCodeScanned: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var scanned by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val barcodeScanner = BarcodeScanning.getClient()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (scanned) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        if (barcode.valueType == Barcode.TYPE_TEXT ||
                                            barcode.valueType == Barcode.TYPE_URL
                                        ) {
                                            val value = barcode.rawValue ?: barcode.url?.url
                                            if (value != null) {
                                                scanned = true
                                                onQrCodeScanned(value)
                                                break
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e("QrScanner", "Barcode scan failed", e)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ctx as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QrScanner", "Camera init failed", e)
                    onError("相机初始化失败: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
