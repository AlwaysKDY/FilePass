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
                var result = apiClient.sendFile(uri, applicationContext)
                // 失败时尝试重新发现一次
                if (result.isFailure) {
                    val rediscovered = findWorkingPc(applicationContext, config, 5_000L)
                    if (rediscovered != null) {
                        result = ApiClient(config.baseUrl).sendFile(uri, applicationContext)
                    }
                }
                result
                    .onSuccess { name -> showToast("✓ 已发送: $name") }
                    .onFailure { e ->
                        val reason = when {
                            e.message?.contains("413") == true -> "文件超过大小限制 (500 MB)"
                            e.message?.contains("timeout") == true || e.message?.contains("connect") == true -> "连接超时，请检查网络"
                            else -> e.message ?: "未知错误"
                        }
                        showToast("✗ 发送失败: $reason")
                    }
                finish()
            }
            text != null -> {
                var result = apiClient.sendText(text)
                if (result.isFailure) {
                    val rediscovered = findWorkingPc(applicationContext, config, 5_000L)
                    if (rediscovered != null) {
                        result = ApiClient(config.baseUrl).sendText(text)
                    }
                }
                result
                    .onSuccess { len -> showToast("✓ 已推送 $len 字到 PC") }
                    .onFailure { e -> showToast("✗ 发送失败: ${e.message ?: "未知错误"}") }
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

        var success = 0
        val failures = mutableListOf<String>()
        for (uri in uris) {
            apiClient.sendFile(uri, applicationContext)
                .onSuccess { success++ }
                .onFailure { e ->
                    val reason = when {
                        e.message?.contains("413") == true -> "超过大小限制"
                        e.message?.contains("timeout") == true || e.message?.contains("connect") == true -> "连接超时"
                        else -> e.message ?: "未知错误"
                    }
                    failures.add(reason)
                }
        }
        val total = uris.size
        if (failures.isEmpty()) {
            showToast("✓ 全部发送成功 ($total 个文件)")
        } else {
            showToast("发送完成: $success 成功, ${failures.size} 失败\n原因: ${failures.first()}")
        }
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
