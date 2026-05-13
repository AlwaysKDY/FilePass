package com.filepass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.filepass.config.AppConfig
import com.filepass.config.RecentFile
import com.filepass.network.ApiClient
import com.filepass.network.DiscoveredDevice
import com.filepass.network.PushFile
import com.filepass.network.findAllDevices
import com.filepass.network.findWorkingPc
import com.filepass.ui.theme.FilePassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pcHostname by remember { mutableStateOf("") }
    var pcAddr by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }
    var showFileListDialog by remember { mutableStateOf(false) }
    var showDeviceSwitcher by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showHowToUse by remember { mutableStateOf(false) }
    var showFileMenu by remember { mutableStateOf<RecentFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<RecentFile?>(null) }
    var pushFiles by remember { mutableStateOf<List<PushFile>>(emptyList()) }
    var pushDir by remember { mutableStateOf("") }
    var pushCapped by remember { mutableStateOf(false) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf(config.getRecentDownloads()) }
    var isTransferring by remember { mutableStateOf(false) }
    var transferMessage by remember { mutableStateOf("") }

    suspend fun doConnect() {
        status = ConnectionStatus.Searching
        val result = findWorkingPc(context, config)
        if (result != null) {
            val (host, port) = result
            pcAddr = "$host:$port"
            status = ConnectionStatus.Testing
            ApiClient("http://$host:$port").ping(onInfo = { mb -> config.maxFileMb = mb })
                .onSuccess { name ->
                    pcHostname = name
                    pcName = config.getDeviceAlias(host, port) ?: ""
                    status = ConnectionStatus.Connected(
                        pcName.ifEmpty { name }
                    )
                }
                .onFailure {
                    pcHostname = host
                    pcName = config.getDeviceAlias(host, port) ?: ""
                    status = ConnectionStatus.Connected(
                        pcName.ifEmpty { host }
                    )
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
                                pcHostname = name
                                pcName = config.getDeviceAlias(host, port) ?: ""
                                pcAddr = "$host:$port"
                                status = ConnectionStatus.Connected(
                                    pcName.ifEmpty { name }
                                )
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
                actions = {
                    IconButton(
                        onClick = {
                            if (!isRefreshing && status !is ConnectionStatus.Searching) {
                                isRefreshing = true
                                scope.launch {
                                    try { doConnect() }
                                    catch (e: Exception) {
                                        status = ConnectionStatus.Failed("刷新失败: ${e.message}")
                                    } finally { isRefreshing = false }
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新连接",
                            tint = if (isRefreshing || status is ConnectionStatus.Searching)
                                MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary
                        )
                    }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            StatusSection(
                status = status,
                alias = pcName,
                hostname = pcHostname,
                addr = pcAddr,
                onRename = {
                    if (config.pcHost.isNotEmpty()) {
                        showRenameDialog = config.pcHost to config.pcPort
                    }
                }
            )

            // 已连接时显示「切换设备」按钮
            if (status is ConnectionStatus.Connected) {
                OutlinedButton(
                    onClick = { showDeviceSwitcher = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("\uD83D\uDD04 更换连接设备", fontSize = 14.sp)
                }
            }

            // PC→手机操作区（仅已连接时显示）
            if (status is ConnectionStatus.Connected) {
                PullFromPcSection(
                    onGetClipboard = {
                        scope.launch {
                            isTransferring = true
                            transferMessage = "正在获取剪贴板…"
                            try {
                                val api = ApiClient(config.baseUrl)
                                api.getClipboard()
                                    .onSuccess { text ->
                                        if (text.isNotEmpty()) {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                            cm.setPrimaryClip(
                                                ClipData.newPlainText("FilePass", text)
                                            )
                                            Toast.makeText(context,
                                                "✓ 已复制到手机剪贴板 (${text.length} 字)",
                                                Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "PC 剪贴板为空",
                                                Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .onFailure { e ->
                                        Toast.makeText(context,
                                            "✗ 获取失败: ${e.message}",
                                            Toast.LENGTH_SHORT).show()
                                    }
                            } finally { isTransferring = false }
                        }
                    },
                    onGetFiles = {
                        isLoadingFiles = true
                        isTransferring = true
                        transferMessage = "正在获取文件列表…"
                        scope.launch {
                            try {
                                ApiClient(config.baseUrl).listPushFiles()
                                    .onSuccess { (files, dir, capped) ->
                                        pushFiles = files
                                        pushDir = dir
                                        pushCapped = capped
                                        showFileListDialog = true
                                    }
                                    .onFailure { e ->
                                        Toast.makeText(context,
                                            "✗ 获取文件列表失败: ${e.message}",
                                            Toast.LENGTH_SHORT).show()
                                    }
                            } finally {
                                isLoadingFiles = false
                                isTransferring = false
                            }
                        }
                    },
                    isLoadingFiles = isLoadingFiles
                )
            }

            // 最近接收文件栏（增强版）
            RecentFilesSection(
                files = recentFiles,
                onFileClick = { rf ->
                    val uri = Uri.parse(rf.uriString)
                    val ext = rf.name.substringAfterLast('.', "").lowercase()
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(intent) }
                    catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
                    }
                },
                onFileMenu = { rf -> showFileMenu = rf },
                onOpenFolder = {
                    try {
                        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FFilePass%2F%E6%8E%A5%E6%94%B6%E6%96%87%E4%BB%B6")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "vnd.android.document/directory")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val uri2 = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FFilePass")
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri2, "vnd.android.document/directory")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开文件管理器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

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
                OutlinedButton(
                    onClick = { showDeviceSwitcher = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("\uD83D\uDD04 扫描所有设备", fontSize = 14.sp)
                }
            }

            // 使用方法（底部小按钮，点击弹窗）
            HowToUseButton(onClick = { showHowToUse = true })

            Spacer(Modifier.height(8.dp))
        }
    }

    // 文件列表对话框
    if (showFileListDialog) {
        FileListDialog(
            files = pushFiles,
            dir = pushDir,
            capped = pushCapped,
            onDismiss = { showFileListDialog = false },
            onDownload = { file ->
                showFileListDialog = false
                isTransferring = true
                transferMessage = "正在下载 ${file.displayName}…"
                scope.launch {
                    try {
                        ApiClient(config.baseUrl).downloadFile(file.name, context)
                            .onSuccess { (name, uri) ->
                                config.addRecentDownload(name, uri.toString())
                                recentFiles = config.getRecentDownloads()
                                Toast.makeText(context, "✓ 已保存: $name",
                                    Toast.LENGTH_LONG).show()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, "✗ 下载失败: ${e.message}",
                                    Toast.LENGTH_SHORT).show()
                            }
                    } finally { isTransferring = false }
                }
            },
            onDeleteFromPc = { file ->
                isTransferring = true
                transferMessage = "正在删除 ${file.displayName}…"
                scope.launch {
                    try {
                        ApiClient(config.baseUrl).deletePushFile(file.name)
                            .onSuccess {
                                pushFiles = pushFiles.filter { it.name != file.name }
                                Toast.makeText(context, "✓ 已从电脑待传区删除: ${file.displayName}",
                                    Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, "✗ 删除失败: ${e.message}",
                                    Toast.LENGTH_SHORT).show()
                            }
                    } finally { isTransferring = false }
                }
            }
        )
    }

    // 切换设备对话框
    if (showDeviceSwitcher) {
        DeviceSwitcherDialog(
            config = config,
            onDismiss = { showDeviceSwitcher = false },
            onSelect = { dev ->
                showDeviceSwitcher = false
                config.pcHost = dev.host
                config.pcPort = dev.port
                config.isConnected = true
                config.addToIpLibrary(dev.host, dev.port)
                pcHostname = dev.hostname
                pcName = config.getDeviceAlias(dev.host, dev.port) ?: ""
                pcAddr = "${dev.host}:${dev.port}"
                status = ConnectionStatus.Connected(
                    pcName.ifEmpty { dev.hostname }
                )
                scope.launch {
                    ApiClient(config.baseUrl).ping(onInfo = { mb -> config.maxFileMb = mb })
                }
            },
            onRename = { host, port -> showRenameDialog = host to port },
            onManualInput = {
                showDeviceSwitcher = false
                showManualDialog = true
            }
        )
    }

    // 重命名设备对话框
    showRenameDialog?.let { (host, port) ->
        RenameDeviceDialog(
            host = host,
            port = port,
            currentAlias = config.getDeviceAlias(host, port) ?: "",
            onDismiss = { showRenameDialog = null },
            onConfirm = { alias ->
                config.setDeviceAlias(host, port, alias)
                showRenameDialog = null
                if (host == config.pcHost && port == config.pcPort) {
                    pcName = alias
                    status = ConnectionStatus.Connected(
                        alias.ifEmpty { pcHostname.ifEmpty { host } }
                    )
                }
            }
        )
    }

    // 文件操作菜单
    showFileMenu?.let { rf ->
        FileActionMenuDialog(
            file = rf,
            onDismiss = { showFileMenu = null },
            onOpen = {
                showFileMenu = null
                val uri = Uri.parse(rf.uriString)
                val ext = rf.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { context.startActivity(intent) }
                catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenWith = {
                showFileMenu = null
                val uri = Uri.parse(rf.uriString)
                val ext = rf.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "选择打开方式")
                try { context.startActivity(chooser) }
                catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                showFileMenu = null
                val uri = Uri.parse(rf.uriString)
                val ext = rf.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "分享到")
                try { context.startActivity(chooser) }
                catch (e: Exception) {
                    Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = {
                showFileMenu = null
                showDeleteConfirm = rf
            }
        )
    }

    // 删除确认弹窗
    showDeleteConfirm?.let { rf ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要永久删除「${rf.name}」吗？\n此操作不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = Uri.parse(rf.uriString)
                        try {
                            context.contentResolver.delete(uri, null, null)
                            config.removeRecentDownload(rf.name)
                            recentFiles = config.getRecentDownloads()
                            Toast.makeText(context, "已删除 ${rf.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }

    // 使用方法弹窗
    if (showHowToUse) {
        HowToUseDialog(
            maxFileMb = config.maxFileMb,
            onDismiss = { showHowToUse = false }
        )
    }

    // 传输中全屏遮罩动画
    if (isTransferring) {
        TransferOverlay(message = transferMessage)
    }
}

// ==================== 组件 ====================

@Composable
fun PullFromPcSection(
    onGetClipboard: () -> Unit,
    onGetFiles: () -> Unit,
    isLoadingFiles: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "从 PC 获取",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGetClipboard,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("\uD83D\uDCCB 剪贴板", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onGetFiles,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    enabled = !isLoadingFiles
                ) {
                    if (isLoadingFiles) {
                        LinearProgressIndicator(
                            modifier = Modifier.size(width = 14.dp, height = 2.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("\uD83D\uDCC1 获取文件", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun RecentFilesSection(
    files: List<RecentFile>,
    onFileClick: (RecentFile) -> Unit,
    onFileMenu: (RecentFile) -> Unit,
    onOpenFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "最近接收",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOpenFolder,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("\uD83D\uDCC2 打开文件夹", fontSize = 11.sp)
                }
            }
            if (files.isEmpty()) {
                Text(
                    "暂无接收文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 使用普通 Column（外部已有 verticalScroll）
                    files.forEach { rf ->
                        RecentFileCard(rf, onFileClick, onFileMenu)
                    }
                }
            }
        }
    }
}

@Composable
fun RecentFileCard(file: RecentFile, onClick: (RecentFile) -> Unit, onMenu: (RecentFile) -> Unit) {
    Card(
        onClick = { onClick(file) },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件类型图标
            Text(fileTypeEmoji(file.name), fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            // 文件名（完整显示，多行）
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 三点菜单
            IconButton(
                onClick = { onMenu(file) },
                modifier = Modifier.size(32.dp)
            ) {
                Text("\u22EE", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FileActionMenuDialog(
    file: RecentFile,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("\uD83D\uDCC4  打开", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(
                    onClick = onOpenWith,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("\uD83D\uDCF1  选择打开方式", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("\u2197\uFE0F  分享", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "\uD83D\uDDD1\uFE0F  删除",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun FileListDialog(
    files: List<PushFile>,
    dir: String,
    capped: Boolean,
    onDismiss: () -> Unit,
    onDownload: (PushFile) -> Unit,
    onDeleteFromPc: (PushFile) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC 端文件 (${files.size}${if (capped) "+" else ""})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (files.isEmpty()) {
                    val hint = if (dir.isNotEmpty()) dir else "FilePass_ToPhone"
                    Text(
                        "暂无文件\n\n将文件放入电脑文件夹：\n$hint",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (capped) {
                        Text(
                            "\u26A0\uFE0F 仅显示前 ${files.size} 个文件，请整理待传文件夹",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        files.forEach { file ->
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (file.name.contains('/')) {
                                            Text(
                                                file.name.substringBeforeLast('/'),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            file.displaySize,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // 下载按钮
                                    TextButton(
                                        onClick = { onDownload(file) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("下载", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    // 删除按钮（✗）
                                    IconButton(
                                        onClick = { onDeleteFromPc(file) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "从电脑待传区删除",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {}
    )
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
fun StatusSection(
    status: ConnectionStatus,
    alias: String,
    hostname: String,
    addr: String,
    onRename: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    // 备注名 + 重命名按钮
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (alias.isNotEmpty()) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text(
                                "未设置备注",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "重命名",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    // 电脑名称
                    if (hostname.isNotEmpty()) {
                        Text(
                            "\uD83D\uDCBB $hostname",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    // IP 地址
                    if (addr.isNotEmpty()) {
                        Text(
                            "\uD83C\uDF10 $addr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
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
fun HowToUseButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Text("使用方法", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun HowToUseDialog(maxFileMb: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("使用方法")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UsageItem("\uD83D\uDCCB", "推送剪贴板", "复制内容 \u2192 下拉通知栏 \u2192 点击 FilePass 磁贴")
                UsageItem("\uD83D\uDCC1", "发送文件", "选中文件 \u2192 分享 \u2192 选择 FilePass（最大 $maxFileMb MB）")
                UsageItem("\uD83D\uDDBC\uFE0F", "发送图片", "相册选图 \u2192 分享 \u2192 选择 FilePass（最大 $maxFileMb MB）")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                UsageItem("\uD83D\uDCCB", "获取电脑剪贴板", "点击「从 PC 获取 \u2192 获取剪贴板」")
                UsageItem("\uD83D\uDCE5", "获取电脑文件", "右键 \u2192 发送到 \u2192 FilePass \u2192 点击「获取文件」")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
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

/** 文件类型 -> emoji 图标 */
private fun fileTypeEmoji(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "\uD83D\uDDBC\uFE0F"
        "mp4", "mov", "avi", "mkv", "wmv" -> "\uD83C\uDFAC"
        "mp3", "aac", "flac", "wav", "ogg" -> "\uD83C\uDFB5"
        "pdf" -> "\uD83D\uDCC4"
        "doc", "docx" -> "\uD83D\uDCDD"
        "xls", "xlsx" -> "\uD83D\uDCCA"
        "ppt", "pptx" -> "\uD83D\uDCD1"
        "zip", "rar", "7z", "tar", "gz" -> "\uD83D\uDCE6"
        "apk" -> "\uD83D\uDCF1"
        "txt", "md" -> "\uD83D\uDCC3"
        else -> "\uD83D\uDCCE"
    }
}

// ==================== 设备切换 ====================

@Composable
fun DeviceSwitcherDialog(
    config: AppConfig,
    onDismiss: () -> Unit,
    onSelect: (DiscoveredDevice) -> Unit,
    onRename: (host: String, port: Int) -> Unit,
    onManualInput: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isScanning = true
        devices = findAllDevices(context, config, 6_000L)
        isScanning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择设备", modifier = Modifier.weight(1f))
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            isScanning = true
                            scope.launch {
                                devices = findAllDevices(context, config, 6_000L)
                                isScanning = false
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新扫描",
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isScanning && devices.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("正在扫描局域网设备\u2026",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (devices.isEmpty() && !isScanning) {
                    Text(
                        "未发现可用设备\n请确保 PC 端 FilePass 正在运行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    )
                } else {
                    devices.forEach { dev ->
                        val alias = config.getDeviceAlias(dev.host, dev.port)
                        val displayName = alias ?: dev.hostname
                        val isCurrent = dev.host == config.pcHost && dev.port == config.pcPort

                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(dev) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isCurrent) 2.dp else 0.dp
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium)
                                        if (isCurrent) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text("${dev.host}:${dev.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (alias != null) {
                                        Text(dev.hostname,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                IconButton(
                                    onClick = { onRename(dev.host, dev.port) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("\u270F\uFE0F", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onManualInput) { Text("手动输入 IP") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun RenameDeviceDialog(
    host: String,
    port: Int,
    currentAlias: String,
    onDismiss: () -> Unit,
    onConfirm: (alias: String) -> Unit
) {
    var nameInput by remember { mutableStateOf(currentAlias) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$host:$port",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("备注名称") },
                    placeholder = { Text("如：公司电脑、家里台式机") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (currentAlias.isNotEmpty()) {
                    TextButton(onClick = { nameInput = ""; onConfirm("") }) {
                        Text("清除备注", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(nameInput.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ==================== 传输遮罩 ====================

@Composable
fun TransferOverlay(message: String) {
    Dialog(
        onDismissRequest = { /* 不允许关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ==================== 状态 ====================

sealed class ConnectionStatus {
    data object Unknown : ConnectionStatus()
    data object Searching : ConnectionStatus()
    data object Testing : ConnectionStatus()
    data class Connected(val pcName: String) : ConnectionStatus()
    data class Failed(val reason: String) : ConnectionStatus()
}
