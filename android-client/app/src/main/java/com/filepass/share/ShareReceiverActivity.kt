package com.filepass.share

import android.content.Intent
import android.net.Uri
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

class ShareReceiverActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = AppConfig(applicationContext)

        scope.launch {
            // 自动发现（包括已知 IP 库 + mDNS）
            val found = findWorkingPc(applicationContext, config, 8_000L)
            if (found == null) {
                showToast("未找到 PC，请先打开 FilePass 应用")
                finish()
                return@launch
            }

            val apiClient = ApiClient(config.baseUrl)

            when (intent?.action) {
                Intent.ACTION_SEND -> handleSend(apiClient, config)
                Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(apiClient, config)
                else -> {
                    showToast("不支持的分享类型")
                    finish()
                }
            }
        }
    }

    private suspend fun handleSend(apiClient: ApiClient, config: AppConfig) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        when {
            uri != null -> {
                showToast("正在发送文件...")
                var result = apiClient.sendFile(uri, applicationContext)
                // 失败时尝试重新发现
                if (result.isFailure) {
                    if (autoDiscover(config)) {
                        val retryClient = ApiClient(config.baseUrl)
                        result = retryClient.sendFile(uri, applicationContext)
                    }
                }
                result.onSuccess { name -> showToast("已发送: $name") }
                    .onFailure { e -> showToast("发送失败: ${e.message}") }
                finish()
            }
            text != null -> {
                var result = apiClient.sendText(text)
                if (result.isFailure) {
                    if (autoDiscover(config)) {
                        val retryClient = ApiClient(config.baseUrl)
                        result = retryClient.sendText(text)
                    }
                }
                result.onSuccess { len -> showToast("已推送 ${len} 字") }
                    .onFailure { e -> showToast("发送失败: ${e.message}") }
                finish()
            }
            else -> {
                showToast("无内容可发送")
                finish()
            }
        }
    }

    private suspend fun handleSendMultiple(apiClient: ApiClient, config: AppConfig) {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris.isNullOrEmpty()) {
            showToast("无文件可发送")
            finish()
            return
        }

        showToast("正在发送 ${uris.size} 个文件...")
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

    private suspend fun autoDiscover(config: AppConfig): Boolean {
        return findWorkingPc(applicationContext, config, 5_000L) != null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
