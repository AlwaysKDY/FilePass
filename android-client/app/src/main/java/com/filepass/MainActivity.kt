package com.filepass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.filepass.config.AppConfig
import com.filepass.network.ApiClient
import com.filepass.network.PcDiscovery
import com.filepass.service.KeepAliveService
import com.filepass.ui.theme.FilePassTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pcDiscovery: PcDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FilePassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        config = AppConfig(applicationContext),
                        onStartDiscovery = { onFound -> startDiscovery(onFound) },
                        onStopDiscovery = { stopDiscovery() },
                        onStartService = { startKeepAliveService() },
                        onStopService = { stopKeepAliveService() },
                    )
                }
            }
        }
    }

    private fun startDiscovery(onFound: (String, Int) -> Unit) {
        pcDiscovery?.stopDiscovery()
        pcDiscovery = PcDiscovery(applicationContext)
        pcDiscovery?.startDiscovery(onFound)
    }

    private fun stopDiscovery() {
        pcDiscovery?.stopDiscovery()
    }

    private fun startKeepAliveService() {
        startForegroundService(Intent(this, KeepAliveService::class.java))
    }

    private fun stopKeepAliveService() {
        startService(
            Intent(this, KeepAliveService::class.java)
                .setAction(KeepAliveService.ACTION_STOP)
        )
    }

    override fun onDestroy() {
        pcDiscovery?.stopDiscovery()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: AppConfig,
    onStartDiscovery: ((String, Int) -> Unit) -> Unit,
    onStopDiscovery: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(config.pcHost) }
    var port by remember { mutableStateOf(config.pcPort.toString()) }
    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Unknown) }
    var isSearching by remember { mutableStateOf(false) }
    var serviceRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FilePass") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 连接状态 ──
            StatusCard(status)

            // ── PC 地址配置 ──
            Text("PC 连接配置", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("PC IP 地址") },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // ── 操作按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 自动发现
                OutlinedButton(
                    onClick = {
                        isSearching = true
                        status = ConnectionStatus.Searching
                        onStartDiscovery { foundHost, foundPort ->
                            host = foundHost
                            port = foundPort.toString()
                            isSearching = false
                            onStopDiscovery()

                            // 自动保存并测试连接
                            config.pcHost = foundHost
                            config.pcPort = foundPort
                            scope.launch {
                                val api = ApiClient(config.baseUrl)
                                api.ping()
                                    .onSuccess { name ->
                                        status = ConnectionStatus.Connected(name)
                                        config.isConnected = true
                                    }
                                    .onFailure { e ->
                                        status = ConnectionStatus.Failed(e.message ?: "未知错误")
                                        config.isConnected = false
                                    }
                            }
                        }
                    },
                    enabled = !isSearching,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (isSearching) "搜索中..." else "自动发现")
                }

                // 保存并测试
                Button(
                    onClick = {
                        val portInt = port.toIntOrNull() ?: AppConfig.DEFAULT_PORT
                        config.pcHost = host
                        config.pcPort = portInt
                        status = ConnectionStatus.Testing

                        scope.launch {
                            val api = ApiClient(config.baseUrl)
                            api.ping()
                                .onSuccess { name ->
                                    status = ConnectionStatus.Connected(name)
                                    config.isConnected = true
                                }
                                .onFailure { e ->
                                    status = ConnectionStatus.Failed(e.message ?: "未知错误")
                                    config.isConnected = false
                                }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存并测试")
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 后台保活 ──
            Text("后台服务", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onStartService()
                        serviceRunning = true
                    },
                    enabled = !serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动保活服务")
                }
                OutlinedButton(
                    onClick = {
                        onStopService()
                        serviceRunning = false
                    },
                    enabled = serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止服务")
                }
            }

            // ── 使用说明 ──
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("使用方法", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("1. 确保手机与 PC 在同一局域网", style = MaterialTheme.typography.bodySmall)
                    Text("2. 下拉通知栏，添加「FilePass」快捷开关", style = MaterialTheme.typography.bodySmall)
                    Text("3. 复制内容后点击 Tile 即可推送到 PC", style = MaterialTheme.typography.bodySmall)
                    Text("4. 文件可通过系统分享菜单直接发送", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun StatusCard(status: ConnectionStatus) {
    val (icon, text, color) = when (status) {
        is ConnectionStatus.Unknown -> Triple(Icons.Default.Warning, "未配置", MaterialTheme.colorScheme.outline)
        is ConnectionStatus.Searching -> Triple(Icons.Default.Search, "正在搜索 PC...", MaterialTheme.colorScheme.tertiary)
        is ConnectionStatus.Testing -> Triple(Icons.Default.Search, "正在测试连接...", MaterialTheme.colorScheme.tertiary)
        is ConnectionStatus.Connected -> Triple(Icons.Default.CheckCircle, "已连接: ${status.pcName}", MaterialTheme.colorScheme.primary)
        is ConnectionStatus.Failed -> Triple(Icons.Default.Warning, "连接失败: ${status.reason}", MaterialTheme.colorScheme.error)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
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
