package com.filepass.tile

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.filepass.config.AppConfig
import com.filepass.network.ApiClient
import com.filepass.network.findWorkingPc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 透明 Activity：拥有前台窗口焦点，能在 Android 10+ 正常读取剪贴板。
 * 由 PushTileService 点击时启动，获取焦点后立即读取+发送，然后关闭。
 */
class ClipboardSendActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不在这里读剪贴板——透明 Activity 此时可能还没拿到焦点，
        // Android 10+ 未获焦点时读剪贴板会返回 null。
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()

        if (text.isNullOrEmpty()) {
            toast("剪贴板为空，请先复制内容")
            finish()
            return
        }

        val config = AppConfig(applicationContext)

        scope.launch {
            val found = findWorkingPc(applicationContext, config, 6_000L)
            if (found == null) {
                toast("未找到 PC，请先打开 FilePass 应用")
                finish()
                return@launch
            }

            ApiClient(config.baseUrl).sendText(text)
                .onSuccess { len -> toast("已推送到 PC ✓  ($len 字)") }
                .onFailure { e -> toast("发送失败: ${e.message}") }

            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
