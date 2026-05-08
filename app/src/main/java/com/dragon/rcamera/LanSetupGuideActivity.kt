package com.dragon.rcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dragon.rcamera.ui.theme.RCameraTheme

class LanSetupGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RCameraTheme {
                LanSetupGuideScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSetupGuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外网连接配置教程") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Overview
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "概述",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "外网手机要连接局域网内的远程摄像头，需要通过 IPv6 实现点对点直连。请按照以下步骤配置您的光猫和路由器，关闭 IPv6 防火墙以允许外网访问。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 1: 光猫设置
            StepHeader(
                stepNumber = 1,
                title = "入户光猫设置",
                icon = Icons.Default.Settings
            )

            Spacer(modifier = Modifier.height(8.dp))

            StepInstruction("打开电脑的浏览器，输入 http://192.168.1.1/ 并回车，进入光猫登录界面。")

            Spacer(modifier = Modifier.height(8.dp))
            GuideImage(imageRes = R.drawable.login)

            Spacer(modifier = Modifier.height(12.dp))
            StepInstruction("输入光猫的用户名和密码进行登录。用户名和密码可以在光猫背面的标签上找到。")

            Spacer(modifier = Modifier.height(8.dp))
            GuideImage(imageRes = R.drawable.user)

            Spacer(modifier = Modifier.height(12.dp))
            StepInstruction("登录成功后，进入「安全」→「防火墙」，关闭 IPv6 防火墙。此步骤允许外网设备通过 IPv6 地址访问局域网内的设备。")

            Spacer(modifier = Modifier.height(8.dp))
            GuideImage(imageRes = R.drawable.modem)

            Spacer(modifier = Modifier.height(20.dp))

            // Step 2: 路由器设置
            StepHeader(
                stepNumber = 2,
                title = "路由器设置",
                icon = Icons.Default.Router
            )

            Spacer(modifier = Modifier.height(8.dp))
            StepInstruction("登录家庭路由器管理页面，将路由器的工作模式设置为「中继模式」。中继模式下，路由器不会创建新的子网，光猫分配的 IPv6 地址可以直达连接的设备。")

            Spacer(modifier = Modifier.height(8.dp))
            GuideImage(imageRes = R.drawable.router)

            Spacer(modifier = Modifier.height(20.dp))

            // Step 3: 完成
            StepHeader(
                stepNumber = 3,
                title = "开始使用",
                icon = Icons.Default.CheckCircle
            )

            Spacer(modifier = Modifier.height(8.dp))
            StepInstruction("配置完成后，局域网内的手机可以启动「打开相机」分享摄像头画面。")
            Spacer(modifier = Modifier.height(4.dp))
            StepInstruction("外网手机点击「浏览远程摄像头」，添加局域网手机的连接地址，即可实时查看远程摄像头拍摄的内容。")

            Spacer(modifier = Modifier.height(16.dp))

            // Tips
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "注意事项",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TipItem("确保两端手机的网络运营商均支持 IPv6")
                    TipItem("光猫关闭 IPv6 防火墙后，局域网内设备将暴露在公网中，请设置强密码")
                    TipItem("部分运营商可能需要重启光猫才能使 IPv6 配置生效")
                    TipItem("如果路由器不支持中继模式，可尝试关闭路由器的 IPv6 防火墙功能")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepHeader(stepNumber: Int, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "$stepNumber",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun StepInstruction(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun GuideImage(imageRes: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp
    ) {
        Image(
            painter = painterResource(id = imageRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter
        )
    }
}

@Composable
private fun TipItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}
