package com.filepass.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 持久化配置管理：Token、PC 地址、端口。
 * 使用 SharedPreferences 存储，轻量无依赖。
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var pcHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    var pcPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit { putBoolean(KEY_CONNECTED, value) }

    val baseUrl: String
        get() = if (pcHost.isNotEmpty()) "http://$pcHost:$pcPort" else ""

    val isConfigured: Boolean
        get() = pcHost.isNotEmpty() && token.isNotEmpty()

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS_NAME = "filepass_config"
        private const val KEY_HOST = "pc_host"
        private const val KEY_PORT = "pc_port"
        private const val KEY_TOKEN = "token"
        private const val KEY_CONNECTED = "connected"
        const val DEFAULT_PORT = 8765
    }
}
