package com.filepass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filepass.config.AppConfig
import com.filepass.network.ApiClient
import com.filepass.network.findWorkingPc
import com.filepass.ui.theme.FilePassTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilePassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(config = AppConfig(applicationContext))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(config: AppConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Unknown) }
    var pcName by remember { mutableStateOf("") }
    var pcAddr by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    // 多策略连接：先试已知IP库，再mDNS
    suspend fun doConnect() {
        status = ConnectionStatus.Searching
        val result = findWorkingPc(context, config)
        if (result != null) {
            val (host, port) = result
            pcAddr = "$host:$port"
            status = ConnectionStatus.Testing
            ApiClient("http://$host:$port").ping()
                .onSuccess { name ->
                    pcName = name
                    status = ConnectionStatus.Connected(name)
                }
                .onFailure { e ->
                    status = ConnectionStatus.Failed(e.message ?: "连接失败")
                }
        } else {
            status = ConnectionStatus.Failed("未找到 PC\n请确保电脑已开启 FilePass")
        }
    }

    LaunchedEffect(Unit) {
        try { doConnect() }
        catch (e: Exception) { status = ConnectionStatus.Failed("启动失败: ${e.message}") }
    }

    if (showManualDialog) {
        ManualIpDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { host, port ->
                showManualDialog = false
                status = ConnectionStatus.Testing
                scope.launch {
                    try {
                        ApiClient("http://$host:$port").ping()
                            .onSuccess { name ->
                                config.pcHost = host
                                config.pcPort = port
                                config.isConnected = true
                                config.addToIpLibrary(host, port)
                                pcName = name
                                pcAddr = "$host:$port"
                                status = ConnectionStatus.Connected(name)
                            }
                            .onFailure {
                                status = ConnectionStatus.Failed("连接 $host:$port 失败，请检查 IP")
                            }
                    } catch (e: Exception) {
                        status = ConnectionStatus.Failed("连接失败: ${e.message}")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("FilePass", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            StatusSection(status, pcName, pcAddr)

            Spacer(Modifier.height(8.dp))

            HowToUseCard()

            if (status is ConnectionStatus.Failed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try { doConnect() }
                                catch (e: Exception) {
                                    status = ConnectionStatus.Failed("搜索失败: ${e.message}")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新搜索")
                    }
                    Button(
                        onClick = { showManualDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("手动输入 IP")
                    }
                }
            }
        }
    }
}

@Composable
fun ManualIpDialog(
    onDismiss: () -> Unit,
    onConfirm: (host: String, port: Int) -> Unit
) {
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("8765") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入 PC 地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "在电脑托盘图标查看显示的 IP（如 192.168.1.100）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("IP 地址") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val host = ipInput.trim()
                    val port = portInput.trim().toIntOrNull() ?: 8765
                    if (host.isNotEmpty()) onConfirm(host, port)
                },
                enabled = ipInput.trim().isNotEmpty()
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun StatusSection(status: ConnectionStatus, pcName: String, pcAddr: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is ConnectionStatus.Connected -> MaterialTheme.colorScheme.primaryContainer
                is ConnectionStatus.Failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (status) {
                is ConnectionStatus.Connected -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "已连接",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (pcName.isNotEmpty()) {
                        Text(
                            pcName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (pcAddr.isNotEmpty()) {
                        Text(
                            pcAddr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                is ConnectionStatus.Searching, is ConnectionStatus.Testing -> {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (status is ConnectionStatus.Searching) "正在搜索 PC..." else "正在连接...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is ConnectionStatus.Failed -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "未连接",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        status.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }

                is ConnectionStatus.Unknown -> {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "准备连接...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun HowToUseCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "使用方法",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            UsageItem("📋", "推送剪贴板", "复制内容 → 下拉通知栏 → 点击 FilePass 磁贴")
            UsageItem("📁", "发送文件", "选中文件 → 分享 → 选择 FilePass")
            UsageItem("🖼️", "发送图片", "相册选图 → 分享 → 选择 FilePass")
        }
    }
}

@Composable
fun UsageItem(emoji: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

sealed class ConnectionStatus {
    data object Unknown : ConnectionStatus()
    data object Searching : ConnectionStatus()
    data object Testing : ConnectionStatus()
    data class Connected(val pcName: String) : ConnectionStatus()
    data class Failed(val reason: String) : ConnectionStatus()
}
