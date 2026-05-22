package com.dragon.rcamera

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragon.rcamera.data.CameraStore
import com.dragon.rcamera.data.RemoteCamera
import com.dragon.rcamera.ui.theme.RCameraTheme
import com.dragon.rcamera.websocket.WsClientState
import com.dragon.rcamera.websocket.WebSocketManager

class CameraListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            RCameraTheme {
                CameraListScreen(onBack = { finish() })
            }
        }
    }
}

data class CameraWithState(
    val camera: RemoteCamera,
    val connectionState: WsClientState
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cameraStore = remember { CameraStore(context) }
    var cameras by remember { mutableStateOf(cameraStore.getCameras()) }
    var refreshKey by remember { mutableStateOf(0) }

    // 添加摄像头结果回调
    val addCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        cameras = cameraStore.getCameras()
    }

    val onAddCamera: () -> Unit = {
        addCameraLauncher.launch(Intent(context, AddCameraActivity::class.java))
    }

    // 查看摄像头结果回调（退出预览后刷新列表，同步密码等变更）
    val viewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        cameras = cameraStore.getCameras()
    }

    // 摄像头信息对话框状态
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoCamera by remember { mutableStateOf<RemoteCamera?>(null) }

    // 刷新列表
    LaunchedEffect(refreshKey) {
        cameras = cameraStore.getCameras()
    }

    // 摄像头信息对话框
    if (showInfoDialog && infoCamera != null) {
        val cam = infoCamera!!
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(cam.name, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("端口: ${cam.port}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val ipv4s = cam.ipv4Addresses ?: emptyList()
                    val ipv6s = cam.ipv6Addresses ?: emptyList()
                    if (ipv4s.isNotEmpty()) {
                        Text("IPv4:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ipv4s.forEach {
                            Text("  $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (ipv6s.isNotEmpty()) {
                        Text("IPv6:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ipv6s.forEach {
                            Text("  $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (ipv4s.isEmpty() && ipv6s.isEmpty()) {
                        Text("地址: ${cam.wsUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程摄像头") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddCamera,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { innerPadding ->
        if (cameras.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "暂无远程摄像头",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右下角按钮添加你的第一个摄像头",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Surface(
                        onClick = onAddCamera,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "添加远程摄像头",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(cameras, key = { it.id }) { camera ->
                    CameraListItem(
                        camera = camera,
                        onClick = {
                            val intent = Intent(context, CameraViewerActivity::class.java).apply {
                                putExtra(CameraViewerActivity.EXTRA_WS_URL, camera.wsUrl)
                                putExtra(CameraViewerActivity.EXTRA_PASSWORD, camera.password)
                                putExtra(CameraViewerActivity.EXTRA_CAMERA_NAME, camera.name)
                                putStringArrayListExtra(CameraViewerActivity.EXTRA_IPV4_ADDRESSES, ArrayList(camera.ipv4Addresses ?: emptyList()))
                                putStringArrayListExtra(CameraViewerActivity.EXTRA_IPV6_ADDRESSES, ArrayList(camera.ipv6Addresses ?: emptyList()))
                                putExtra(CameraViewerActivity.EXTRA_PORT, camera.port)
                            }
                            viewerLauncher.launch(intent)
                        },
                        onDelete = {
                            cameraStore.removeCamera(camera.id)
                            cameras = cameraStore.getCameras()
                        },
                        onShowInfo = {
                            infoCamera = camera
                            showInfoDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CameraListItem(
    camera: RemoteCamera,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShowInfo: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = camera.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "点击连接预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            IconButton(onClick = onShowInfo) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "查看信息",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
