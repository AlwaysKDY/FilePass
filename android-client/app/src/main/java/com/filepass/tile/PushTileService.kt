package com.filepass.tile

import android.content.ClipboardManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.filepass.config.AppConfig
import com.filepass.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下拉通知栏 Quick Settings Tile — 核心入口。
 * 点击即读取剪贴板并推送到 PC。
 *
 * Android 10+ 剪贴板限制：TileService.onClick() 属于前台交互，
 * 系统允许读取剪贴板内容，是合规方案。
 */
class PushTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val config = AppConfig(applicationContext)
        if (!config.isConfigured) {
            showToast("请先在 FilePass 中配置连接")
            return
        }

        // 读取剪贴板（前台交互，Android 10+ 合规）
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()

        if (text.isNullOrEmpty()) {
            showToast("剪贴板为空")
            return
        }

        // 更新 Tile 状态为"发送中"
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.updateTile()
        }

        val apiClient = ApiClient(config.baseUrl, config.token)
        scope.launch {
            val result = apiClient.sendText(text)
            result.onSuccess { length ->
                showToast("已推送 ${length} 字到 PC")
            }.onFailure { e ->
                showToast("发送失败: ${e.message}")
            }
            // 恢复 Tile 状态
            qsTile?.let {
                it.state = Tile.STATE_ACTIVE
                it.updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val config = AppConfig(applicationContext)
        qsTile?.let {
            it.state = if (config.isConfigured) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "FilePass"
            it.contentDescription = "推送剪贴板到 PC"
            it.updateTile()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
