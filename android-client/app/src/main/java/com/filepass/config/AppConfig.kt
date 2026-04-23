package com.filepass.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** 最近下载的文件记录 */
data class RecentFile(val name: String, val uriString: String, val timestamp: Long)

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

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit { putBoolean(KEY_CONNECTED, value) }

    val baseUrl: String
        get() = if (pcHost.isNotEmpty()) "http://$pcHost:$pcPort" else ""

    val isConfigured: Boolean
        get() = pcHost.isNotEmpty()

    /** 上次 ping 返回的最大文件大小（MB），默认 500，运行时更新 */
    var maxFileMb: Int = 500

    /** 曾经成功连接过的 IP 库，格式 "host:port" */
    val ipLibraryPairs: List<Pair<String, Int>>
        get() = (prefs.getStringSet(KEY_IP_LIBRARY, emptySet()) ?: emptySet()).mapNotNull {
            val idx = it.lastIndexOf(':')
            if (idx < 0) null
            else it.substring(0, idx) to (it.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null)
        }

    fun addToIpLibrary(host: String, port: Int) {
        val lib = (prefs.getStringSet(KEY_IP_LIBRARY, emptySet()) ?: emptySet()).toMutableSet()
        lib.add("$host:$port")
        prefs.edit { putStringSet(KEY_IP_LIBRARY, lib) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    /** 获取最近下载的文件列表（最多 10 条，最新在前）*/
    fun getRecentDownloads(): List<RecentFile> {
        val json = prefs.getString(KEY_RECENT_DOWNLOADS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RecentFile(obj.getString("name"), obj.getString("uri"), obj.getLong("ts"))
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 添加一条下载记录（自动去重、截断为 10 条）*/
    fun addRecentDownload(name: String, uriString: String) {
        val list = getRecentDownloads().toMutableList()
        list.removeAll { it.name == name }
        list.add(0, RecentFile(name, uriString, System.currentTimeMillis()))
        val trimmed = list.take(10)
        val arr = JSONArray()
        trimmed.forEach { rf ->
            arr.put(JSONObject().apply {
                put("name", rf.name)
                put("uri", rf.uriString)
                put("ts", rf.timestamp)
            })
        }
        prefs.edit { putString(KEY_RECENT_DOWNLOADS, arr.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "filepass_config"
        private const val KEY_HOST = "pc_host"
        private const val KEY_PORT = "pc_port"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_IP_LIBRARY = "ip_library"
        private const val KEY_RECENT_DOWNLOADS = "recent_downloads"
        const val DEFAULT_PORT = 8765
    }
}
