# FilePass — 详细技术路线文档

> 最后更新：2026-04-21

---

## 一、整体架构

```
┌─────────────────────────────────────────────────┐
│                   局域网 (Wi-Fi)                  │
│                                                   │
│  ┌──────────────┐    HTTP/REST     ┌────────────┐ │
│  │  Android 端   │ ──────────────→ │   PC 端     │ │
│  │  (Kotlin)     │ ←────────────── │  (Python)   │ │
│  │               │    mDNS 发现     │             │ │
│  └──────────────┘                  └────────────┘ │
│     Quick Tile                      System Tray   │
│     Share Sheet                     FastAPI Server │
│     NsdManager                      zeroconf      │
└─────────────────────────────────────────────────┘
```

### 通信模型

- **协议**：HTTP/1.1 REST API（局域网内，HTTP 足够安全且避免证书管理复杂度）
- **发现**：mDNS (Multicast DNS) — 服务名 `_filepass._tcp.local.`
- **方向**：手机 → PC 为主（V1），PC → 手机为辅（V2）
- **编码**：文本统一 UTF-8，文件使用 multipart/form-data

---

## 二、PC 端技术方案

### 2.1 技术栈

| 组件 | 选型 | 理由 |
|------|------|------|
| Web 框架 | **FastAPI** (uvicorn ASGI) | 异步高性能，空闲时 CPU ≈ 0%，API 风格简洁 |
| mDNS | **zeroconf** | Python 生态最成熟的 mDNS 库 |
| 系统托盘 | **pystray** + Pillow | 纯 Python 实现 Windows 托盘图标 |
| 剪贴板 | **pyperclip** | 跨平台剪贴板读写，轻量 |
| 通知 | **win10toast-click** 或 **plyer** | Windows 10 Toast 通知，不抢焦点 |
| 打包 | **PyInstaller** (onefile) | 生成单个 .exe，用户无需装 Python |
| 运行时 | Python 3.10+ | 类型提示完善、asyncio 成熟 |

### 2.2 项目结构

```
FilePass/
├── docs/
│   ├── PRD.md                    # 产品需求文档
│   └── TECH_ROADMAP.md           # 本文档
├── pc-server/
│   ├── main.py                   # 入口：启动托盘 + 后台服务
│   ├── server.py                 # FastAPI 应用定义
│   ├── mdns_service.py           # zeroconf mDNS 注册/注销
│   ├── clipboard.py              # 剪贴板操作封装
│   ├── tray.py                   # 系统托盘图标 & 菜单
│   ├── notifier.py               # Toast 通知封装
│   ├── auth.py                   # Token 认证中间件
│   ├── config.py                 # 配置管理（端口、保存路径、Token）
│   ├── utils.py                  # 工具函数（IP获取、文件名清洗）
│   ├── requirements.txt
│   ├── build.spec                # PyInstaller 打包配置
│   └── assets/
│       └── icon.png              # 托盘图标
├── android-client/
│   └── (Android 项目)
└── README.md
```

### 2.3 核心模块详设

#### 2.3.1 main.py — 启动流程

```python
# 伪代码
def main():
    config = load_config()
    
    # 1. 启动 FastAPI 服务（后台线程）
    server_thread = Thread(target=run_uvicorn, args=(config,), daemon=True)
    server_thread.start()
    
    # 2. 启动 mDNS 广播（后台线程）
    mdns = MdnsService(config.port)
    mdns.register()
    
    # 3. 启动系统托盘（主线程，阻塞）
    tray = TrayApp(config, mdns)
    tray.run()  # 阻塞直到用户退出
```

**要点**：
- 托盘在主线程运行（pystray 要求）。
- uvicorn 在 daemon 线程内运行，主线程退出时自动结束。
- 全程无任何 GUI 窗口。

#### 2.3.2 server.py — API 设计

```
POST /api/text
  Headers: Authorization: Bearer <token>
  Body: { "content": "要传输的文本" }
  Response: { "status": "ok", "length": 128 }
  副作用: 写入 Windows 剪贴板 + 托盘通知

POST /api/file
  Headers: Authorization: Bearer <token>
  Body: multipart/form-data, field name = "file"
  Response: { "status": "ok", "filename": "photo.jpg", "size": 2400000 }
  副作用: 保存到 ~/Desktop/FilePass_Received/ + 托盘通知

GET /api/ping
  Response: { "status": "ok", "name": "MY-PC", "version": "1.0.0" }
  用途: 手机端检测连接状态

GET /api/clipboard  (V2)
  Headers: Authorization: Bearer <token>
  Response: { "content": "PC剪贴板内容" }
```

**安全措施**：
```python
# 文件名清洗 — 防止路径穿越
import os, re

def sanitize_filename(name: str) -> str:
    # 去除路径分隔符和特殊字符
    name = os.path.basename(name)
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name)
    if not name or name.startswith('.'):
        name = f"file_{int(time.time())}"
    return name
```

```python
# 文件大小限制
MAX_FILE_SIZE = 500 * 1024 * 1024  # 500 MB

@app.post("/api/file")
async def upload_file(file: UploadFile, token: str = Depends(verify_token)):
    if file.size and file.size > MAX_FILE_SIZE:
        raise HTTPException(413, "文件超过大小限制")
    # ... 保存逻辑
```

#### 2.3.3 mdns_service.py — 服务发现

```python
from zeroconf import Zeroconf, ServiceInfo
import socket

class MdnsService:
    SERVICE_TYPE = "_filepass._tcp.local."
    
    def register(self):
        self.zc = Zeroconf()
        info = ServiceInfo(
            type_=self.SERVICE_TYPE,
            name=f"FilePass-{socket.gethostname()}.{self.SERVICE_TYPE}",
            addresses=[socket.inet_aton(self.local_ip)],
            port=self.port,
            properties={"version": "1.0"}
        )
        self.zc.register_service(info)
    
    def unregister(self):
        self.zc.unregister_all_services()
        self.zc.close()
```

#### 2.3.4 tray.py — 系统托盘

```python
import pystray
from PIL import Image

class TrayApp:
    def run(self):
        icon = pystray.Icon(
            "FilePass",
            Image.open("assets/icon.png"),
            "FilePass - 运行中",
            menu=pystray.Menu(
                pystray.MenuItem(f"IP: {self.ip}:{self.port}", None, enabled=False),
                pystray.MenuItem("打开接收文件夹", self.open_folder),
                pystray.MenuItem("退出", self.quit),
            )
        )
        icon.run()
```

**关键**：不调用 `SetForegroundWindow`，不创建任何 `Tk` / `QWidget` 窗口。

#### 2.3.5 config.py — 配置管理

配置文件存储在 `%APPDATA%/FilePass/config.json`：

```json
{
  "port": 8765,
  "save_dir": "~/Desktop/FilePass_Received",
  "token": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "max_file_size_mb": 500,
  "auto_start": false
}
```

- 首次运行自动生成 UUID Token。
- Token 需复制到手机端完成配对。

### 2.4 打包与分发

```bash
# PyInstaller 打包命令
pyinstaller --onefile --noconsole --icon=assets/icon.ico \
  --add-data "assets/icon.png;assets" \
  --name FilePass main.py
```

产物：`dist/FilePass.exe`（预估 20-30 MB），双击运行即在托盘静默启动。

### 2.5 开机自启方案

将快捷方式放入 Windows 启动文件夹：
```
%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\FilePass.lnk
```
或通过注册表：
```
HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
  FilePass = "D:\path\to\FilePass.exe"
```

---

## 三、Android 端技术方案

### 3.1 技术栈

| 组件 | 选型 | 理由 |
|------|------|------|
| 语言 | **Kotlin** | Android 官方推荐，现代语法 |
| 最低 API | **29 (Android 10)** | 覆盖 Vivo OriginOS 全系 |
| 网络 | **OkHttp** | 轻量、稳定，Android 生态标准 |
| mDNS | **NsdManager** (Android 系统API) | 无需额外依赖 |
| UI | **Jetpack Compose** | 界面极简，Compose 开发效率高 |
| 后台 | **Foreground Service** + WorkManager | 保持 Quick Tile 可用 |
| 构建 | **Gradle + AGP** | 标准 Android 构建链 |

### 3.2 项目结构

```
android-client/
├── app/src/main/
│   ├── java/com/filepass/
│   │   ├── MainActivity.kt          # 极简主页面
│   │   ├── tile/
│   │   │   └── PushTileService.kt    # Quick Settings Tile
│   │   ├── share/
│   │   │   └── ShareReceiverActivity.kt  # 系统分享入口
│   │   ├── network/
│   │   │   ├── PcDiscovery.kt        # mDNS 发现逻辑
│   │   │   └── ApiClient.kt          # OkHttp 封装
│   │   ├── service/
│   │   │   └── KeepAliveService.kt   # 轻量前台服务
│   │   └── config/
│   │       └── AppConfig.kt          # Token/IP 持久化
│   ├── res/
│   │   ├── drawable/ic_tile.xml      # Tile 图标
│   │   └── layout/                   # (Compose 可省略)
│   └── AndroidManifest.xml
├── build.gradle.kts
└── gradle/
```

### 3.3 核心模块详设

#### 3.3.1 Quick Settings Tile — 核心入口

```kotlin
class PushTileService : TileService() {
    
    // 用户点击 Tile 时触发
    override fun onClick() {
        super.onClick()
        
        // 1. 读取剪贴板（TileService.onClick 属于前台交互，可以读取）
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
        
        if (text.isNullOrEmpty()) {
            showToast("剪贴板为空")
            return
        }
        
        // 2. 发送到 PC
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ApiClient.sendText(text)
                withContext(Dispatchers.Main) {
                    showToast("已推送 ${text.length} 字")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("发送失败: ${e.message}")
                }
            }
        }
    }
}
```

**Android 10+ 剪贴板限制解决方案**：

`TileService.onClick()` 由用户主动点击触发，系统视为前台交互，此时应用有权读取剪贴板内容。这是绕过 Android 10 后台剪贴板限制的**合规方案**。

#### 3.3.2 Share Sheet — 系统分享入口

```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".share.ShareReceiverActivity"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="*/*" />
    </intent-filter>
</activity>
```

```kotlin
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                
                if (uri != null) {
                    uploadFile(uri)
                } else if (text != null) {
                    sendText(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.forEach { uploadFile(it) }
            }
        }
        
        // 透明 Activity，处理完立即关闭
        finish()
    }
}
```

#### 3.3.3 mDNS 发现

```kotlin
class PcDiscovery(private val context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val SERVICE_TYPE = "_filepass._tcp."
    
    fun startDiscovery(onFound: (host: String, port: Int) -> Unit) {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host.hostAddress
                            val port = resolved.port
                            onFound(host, port)
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    })
                }
                // ... 其他回调
            })
    }
}
```

#### 3.3.4 API 客户端

```kotlin
object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private var baseUrl: String = ""  // 由 mDNS 发现后设置
    private var token: String = ""
    
    suspend fun sendText(text: String): Boolean {
        val json = JSONObject().put("content", text).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/text")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { it.isSuccessful }
        }
    }
    
    suspend fun sendFile(uri: Uri, context: Context): Boolean {
        val resolver = context.contentResolver
        val name = getFileName(resolver, uri) ?: "unknown"
        val stream = resolver.openInputStream(uri) ?: return false
        
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", name, 
                stream.readBytes().toRequestBody("application/octet-stream".toMediaType()))
            .build()
        
        val request = Request.Builder()
            .url("$baseUrl/api/file")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { it.isSuccessful }
        }
    }
}
```

#### 3.3.5 Vivo OriginOS 适配要点

| 问题 | 解决方案 |
|------|---------|
| 后台被杀 | Foreground Service 显示常驻通知「FilePass 已连接」 |
| 自启动被拦截 | 引导用户到 设置→电池→后台功耗管理→FilePass→无限制 |
| Quick Tile 消失 | WorkManager 定期检查 Tile 状态 |
| Wi-Fi 休眠断开 | 申请 `WIFI_MODE_FULL_HIGH_PERF` WifiLock |

### 3.4 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />  <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 四、通信协议详细定义

### 4.1 API 端点

#### POST /api/text

```
Request:
  POST http://192.168.1.100:8765/api/text
  Headers:
    Content-Type: application/json
    Authorization: Bearer <token>
  Body:
    {
      "content": "这是从手机推送的文本"
    }

Response (200):
    {
      "status": "ok",
      "length": 12
    }

Response (401):
    {
      "detail": "认证失败"
    }
```

#### POST /api/file

```
Request:
  POST http://192.168.1.100:8765/api/file
  Headers:
    Authorization: Bearer <token>
  Body: (multipart/form-data)
    file: <binary>

Response (200):
    {
      "status": "ok",
      "filename": "photo_001.jpg",
      "size": 2456789,
      "saved_to": "C:/Users/xxx/Desktop/FilePass_Received/photo_001.jpg"
    }

Response (413):
    {
      "detail": "文件超过大小限制 (500MB)"
    }
```

#### GET /api/ping

```
Request:
  GET http://192.168.1.100:8765/api/ping

Response (200):
    {
      "status": "ok",
      "name": "MY-PC",
      "version": "1.0.0"
    }
```

### 4.2 mDNS 服务描述

| 字段 | 值 |
|------|---|
| Service Type | `_filepass._tcp.local.` |
| Service Name | `FilePass-<hostname>._filepass._tcp.local.` |
| Port | `8765` |
| TXT Records | `version=1.0` |

---

## 五、开发步骤 & 执行计划

### Phase 1：PC 端 MVP（建议先做这一端）

```
步骤 1.1  初始化项目 & 依赖
  └─ 创建 pc-server/，安装 fastapi, uvicorn, zeroconf, pystray, pyperclip, pillow

步骤 1.2  实现 config.py
  └─ 加载/创建 config.json，首次运行生成 UUID Token

步骤 1.3  实现 server.py
  └─ FastAPI app，/api/ping, /api/text, /api/file 三个端点
  └─ Token 校验中间件
  └─ 文件名清洗、大小限制

步骤 1.4  实现 clipboard.py
  └─ pyperclip 封装

步骤 1.5  实现 mdns_service.py
  └─ zeroconf 注册/注销

步骤 1.6  实现 tray.py + notifier.py
  └─ pystray 托盘 + Toast 通知

步骤 1.7  实现 main.py
  └─ 整合所有模块，多线程启动

步骤 1.8  用 curl / Postman 手动测试
  └─ curl -X POST http://localhost:8765/api/text -H "Authorization: Bearer <token>" -d '{"content":"test"}'

步骤 1.9  PyInstaller 打包测试
  └─ 验证单文件 exe 可以正常运行
```

### Phase 2：Android 端 MVP

```
步骤 2.1  创建 Android 项目
  └─ Android Studio / VS Code，Kotlin + Jetpack Compose
  └─ 最低 API 29，Target API 34

步骤 2.2  实现 PcDiscovery.kt
  └─ NsdManager 服务发现

步骤 2.3  实现 ApiClient.kt
  └─ OkHttp 封装 sendText / sendFile

步骤 2.4  实现 MainActivity.kt
  └─ 极简 UI：连接状态 + 手动 IP 输入 + Token 输入

步骤 2.5  实现 PushTileService.kt
  └─ Quick Settings Tile，点击推送剪贴板

步骤 2.6  实现 ShareReceiverActivity.kt
  └─ 注册 Share Sheet，接收文件/文本

步骤 2.7  实现 KeepAliveService.kt
  └─ Foreground Service 保活

步骤 2.8  Vivo OriginOS 实机测试
  └─ 验证后台存活、Tile 可用性、Wi-Fi 保持
```

### Phase 3：联调 & 优化

```
步骤 3.1  双端联调
  └─ 文本传输 + 文件传输全流程

步骤 3.2  错误处理完善
  └─ 网络断开、PC 关机、Token 不匹配 等场景

步骤 3.3  性能验证
  └─ 内存占用 / CPU 占用 / 传输速度 实测

步骤 3.4  打包最终版本
  └─ PC: FilePass.exe
  └─ Android: filepass.apk (签名)
```

---

## 六、关键风险 & 应对

| 风险 | 影响 | 应对 |
|------|------|------|
| Android 10+ 后台无法读取剪贴板 | Quick Tile 核心功能失效 | Tile.onClick() 属于前台交互，系统允许读取。已验证可行 |
| Vivo OriginOS 激进省电杀后台 | Tile 点击无响应 | Foreground Service + 引导用户加白名单 |
| Clash 代理拦截局域网请求 | 连接失败 | Clash 配置绕行 `192.168.0.0/16` 和 `*.local` |
| mDNS 在某些路由器被屏蔽 | 自动发现失败 | 手动输入 IP 降级方案 |
| 大文件传输内存溢出 | App 崩溃 | 分块读写（streaming），不在内存中缓存整个文件 |
| PyInstaller 打包体积过大 | 超出轻量预期 | 可改用 Nuitka 编译，或用 `--exclude-module` 精简 |

---

## 七、依赖版本锁定

### PC 端 requirements.txt

```
fastapi==0.115.0
uvicorn[standard]==0.30.0
zeroconf==0.132.0
pystray==0.19.5
Pillow==10.4.0
pyperclip==1.9.0
plyer==2.1.0
python-multipart==0.0.9
```

### Android 端 build.gradle.kts 关键依赖

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

---

## 八、测试策略

| 层级 | 方法 | 工具 |
|------|------|------|
| PC API 单元测试 | pytest + httpx TestClient | `pytest pc-server/tests/` |
| PC 集成测试 | curl / Postman 手动调用 | 验证剪贴板写入、文件保存 |
| Android 功能测试 | 真机 (Vivo) 手动测试 | 每个场景逐一验证 |
| 端到端测试 | 手机→PC 全流程 | 验证文本/文件/大文件 |
| 性能测试 | 任务管理器观察 | CPU / 内存 / 传输速度 |
| 压力测试 | 连续发送 100 次文本 | 验证无内存泄漏 |

---

## 九、后续演进方向 (V2+)

1. **PC → 手机反向推送**：PC 托盘菜单推送剪贴板到手机。
2. **剪贴板自动同步**：双端剪贴板变化时自动同步（需处理循环问题）。
3. **文件拖拽发送**：手机端拖拽式文件选择。
4. **传输历史**：最近 50 条传输记录，支持重新发送。
5. **WebSocket 长连接**：替代 HTTP 轮询，实现即时推送。
6. **加密传输**：可选 HTTPS（自签证书）或 AES 加密负载。
