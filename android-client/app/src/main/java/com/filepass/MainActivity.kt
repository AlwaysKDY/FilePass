package com.filepass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.filepass.network.PushFile
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
    var pcAddr by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }
    var showFileListDialog by remember { mutableStateOf(false) }
    var pushFiles by remember { mutableStateOf<List<PushFile>>(emptyList()) }
    var pushDir by remember { mutableStateOf("") }
    var pushCapped by remember { mutableStateOf(false) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf(config.getRecentDownloads()) }

    // 多策略连接：先试已知IP库，再mDNS；findWorkingPc 内部已验证 ping，返回非 null 即可用
    suspend fun doConnect() {
        status = ConnectionStatus.Searching
        val result = findWorkingPc(context, config)
        if (result != null) {
            val (host, port) = result
            pcAddr = "$host:$port"
            status = ConnectionStatus.Testing
            ApiClient("http://$host:$port").ping(onInfo = { mb -> config.maxFileMb = mb })
                .onSuccess { name ->
                    pcName = name
                    status = ConnectionStatus.Connected(name)
                }
                .onFailure { e ->
                    // ping 连续两次，极偶发情况才到这里，直接用已知信息置 Connected
                    pcName = host
                    status = ConnectionStatus.Connected(host)
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

            StatusSection(status, pcName, pcAddr)

            // PC→手机操作区（仅已连接时显示）
            if (status is ConnectionStatus.Connected) {
                PullFromPcSection(
                    onGetClipboard = {
                        scope.launch {
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
                        }
                    },
                    onGetFiles = {
                        isLoadingFiles = true
                        scope.launch {
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
                            isLoadingFiles = false
                        }
                    },
                    isLoadingFiles = isLoadingFiles
                )
            }

            HowToUseCard(maxFileMb = config.maxFileMb)

            // 最近下载的文件展示栏
            if (recentFiles.isNotEmpty()) {
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
                    }
                )
            }

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
                scope.launch {
                    Toast.makeText(context, "正在下载 ${file.name}…", Toast.LENGTH_SHORT).show()
                    ApiClient(config.baseUrl).downloadFile(file.name, context)
                        .onSuccess { (name, uri) ->
                            config.addRecentDownload(name, uri.toString())
                            recentFiles = config.getRecentDownloads()
                            Toast.makeText(context, "✓ 已保存到下载: $name",
                                Toast.LENGTH_LONG).show()
                        }
                        .onFailure { e ->
                            Toast.makeText(context, "✗ 下载失败: ${e.message}",
                                Toast.LENGTH_SHORT).show()
                        }
                }
            }
        )
    }

}

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
                    Text("📋 剪贴板", fontSize = 13.sp)
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
                    Text("📁 获取文件", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun FileListDialog(
    files: List<PushFile>,
    dir: String,
    capped: Boolean,
    onDismiss: () -> Unit,
    onDownload: (PushFile) -> Unit
) {
    val context = LocalContext.current
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
                            "⚠️ 仅显示前 ${files.size} 个文件，请整理待传文件夹",
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
                            OutlinedButton(
                                onClick = { onDownload(file) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    // 若在子目录中，显示相对路径
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
                                Text("下载", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 打开系统文件管理器 Downloads 目录
            TextButton(onClick = {
                onDismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "vnd.android.document/directory"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // 降级：用通用文件管理器
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(fallback, "打开文件管理器"))
                    } catch (e2: Exception) { }
                }
            }) { Text("📂 文件管理器") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
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

/** 文件类型 → emoji 图标 */
private fun fileTypeEmoji(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼️"
        "mp4", "mov", "avi", "mkv", "wmv" -> "🎬"
        "mp3", "aac", "flac", "wav", "ogg" -> "🎵"
        "pdf" -> "📄"
        "doc", "docx" -> "📝"
        "xls", "xlsx" -> "📊"
        "ppt", "pptx" -> "📑"
        "zip", "rar", "7z", "tar", "gz" -> "📦"
        "apk" -> "📱"
        "txt", "md" -> "📃"
        else -> "📎"
    }
}

@Composable
fun RecentFilesSection(
    files: List<RecentFile>,
    onFileClick: (RecentFile) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "最近接收",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files) { rf ->
                    RecentFileCard(rf, onFileClick)
                }
            }
        }
    }
}

@Composable
fun RecentFileCard(file: RecentFile, onClick: (RecentFile) -> Unit) {
    val context = LocalContext.current
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    var bitmap by remember(file.uriString) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    if (isImage) {
        LaunchedEffect(file.uriString) {
            withContext(Dispatchers.IO) {
                bitmap = try {
                    context.contentResolver.openInputStream(Uri.parse(file.uriString))?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }?.asImageBitmap()
                } catch (e: Exception) { null }
            }
        }
    }

    Column(
        modifier = Modifier
            .width(80.dp)
            .clickable { onClick(file) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Card(
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(72.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isImage && bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(fileTypeEmoji(file.name), fontSize = 28.sp)
                }
            }
        }
        Text(
            text = file.name.let { if (it.length > 9) it.take(7) + "…" else it },
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun HowToUseCard(maxFileMb: Int = 500) {
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
            UsageItem("📁", "发送文件", "选中文件 → 分享 → 选择 FilePass（最大 ${maxFileMb} MB）")
            UsageItem("🖼️", "发送图片", "相册选图 → 分享 → 选择 FilePass（最大 ${maxFileMb} MB）")
            Divider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)
            UsageItem("📋", "获取电脑剪贴板", "点击「从 PC 获取 → 获取剪贴板」")
            UsageItem("📥", "获取电脑文件", "右键文件 → 发送到 → FilePass (发送到手机) → 点击「获取文件」")        }
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
