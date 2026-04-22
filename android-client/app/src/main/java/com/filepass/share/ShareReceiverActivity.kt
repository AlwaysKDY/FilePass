package com.filepass.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.filepass.config.AppConfig
import com.filepass.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统分享面板（Share Sheet）接收入口。
 * 透明 Activity，处理分享内容后立即关闭。
 * 支持：文本、单文件、多文件。
 */
class ShareReceiverActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = AppConfig(applicationContext)
        if (!config.isConfigured) {
            showToast("请先在 FilePass 中配置连接")
            finish()
            return
        }

        val apiClient = ApiClient(config.baseUrl)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend(apiClient)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(apiClient)
            else -> {
                showToast("不支持的分享类型")
                finish()
            }
        }
    }

    private fun handleSend(apiClient: ApiClient) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        when {
            uri != null -> {
                showToast("正在发送文件...")
                scope.launch {
                    apiClient.sendFile(uri, applicationContext)
                        .onSuccess { name -> showToast("已发送: $name") }
                        .onFailure { e -> showToast("发送失败: ${e.message}") }
                    finish()
                }
            }
            text != null -> {
                scope.launch {
                    apiClient.sendText(text)
                        .onSuccess { len -> showToast("已推送 ${len} 字") }
                        .onFailure { e -> showToast("发送失败: ${e.message}") }
                    finish()
                }
            }
            else -> {
                showToast("无内容可发送")
                finish()
            }
        }
    }

    private fun handleSendMultiple(apiClient: ApiClient) {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris.isNullOrEmpty()) {
            showToast("无文件可发送")
            finish()
            return
        }

        showToast("正在发送 ${uris.size} 个文件...")
        scope.launch {
            var success = 0
            var fail = 0
            for (uri in uris) {
                apiClient.sendFile(uri, applicationContext)
                    .onSuccess { success++ }
                    .onFailure { fail++ }
            }
            showToast("完成: $success 成功, $fail 失败")
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
