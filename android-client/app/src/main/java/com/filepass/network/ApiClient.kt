package com.filepass.network

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * PC 端 API 客户端 — 文本推送 & 文件上传。
 * 所有方法均为 suspend 函数，在 IO 调度器上执行。
 */
class ApiClient(
    private val baseUrl: String,
) {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 发送文本到 PC 剪贴板 */
    suspend fun sendText(text: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().put("content", text).toString()
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/text")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string())
                val respJson = JSONObject(resp.body!!.string())
                respJson.getInt("length")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 上传文件到 PC — streaming，不将整个文件读入内存 */
    suspend fun sendFile(uri: Uri, context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val fileName = getFileName(resolver, uri) ?: "unknown_file"

            val streamBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    val inputStream: InputStream = resolver.openInputStream(uri)
                        ?: throw IOException("无法读取文件")
                    inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, streamBody)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/file")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string())
                val respJson = JSONObject(resp.body!!.string())
                respJson.getString("filename")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 检测 PC 端是否在线，返回主机名，并顺带写入 max_file_mb */
    suspend fun ping(onInfo: ((maxFileMb: Int) -> Unit)? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/ping")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, null)
                val respJson = JSONObject(resp.body!!.string())
                val maxFileMb = respJson.optInt("max_file_mb", 500)
                onInfo?.invoke(maxFileMb)
                respJson.getString("name")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 获取 PC 剪贴板内容 */
    suspend fun getClipboard(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/clipboard")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, null)
                val respJson = JSONObject(resp.body!!.string())
                respJson.getString("content")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 列出 PC 推送目录中的文件，同时返回目录路径和是否达到数量上限 */
    suspend fun listPushFiles(): Result<Triple<List<PushFile>, String, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/push/files")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, null)
                val json = JSONObject(resp.body!!.string())
                val dir = json.optString("dir", "")
                val capped = json.optBoolean("capped", false)
                val arr = json.getJSONArray("files")
                val files = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    PushFile(obj.getString("name"), obj.getLong("size"))
                }
                Triple(files, dir, capped)
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 从 PC 推送目录下载一个文件到手机 Downloads，支持子目录路径 */
    suspend fun downloadFile(filename: String, context: Context): Result<Pair<String, android.net.Uri>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val downloadClient = client.newBuilder()
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                // 对路径每一段单独编码，保留斜杠作为路径分隔符
                val encodedPath = filename.split("/")
                    .joinToString("/") { Uri.encode(it) }
                val request = Request.Builder()
                    .url("$baseUrl/api/push/download/$encodedPath")
                    .get()
                    .build()

                downloadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string())

                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                    val itemUri = resolver.insert(collection, values)
                        ?: throw IOException("无法在 Downloads 创建文件")

                    resolver.openOutputStream(itemUri)?.use { out ->
                        resp.body!!.byteStream().copyTo(out)
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)

                    Pair(filename, itemUri)
                }
            }.recoverCatching { mapNetworkError(it) }
        }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun httpError(code: Int, body: String?): IOException {
        val msg = when (code) {
            413 -> "文件超过大小限制"
            else -> "HTTP $code"
        }
        return IOException(if (body.isNullOrBlank()) msg else "$msg: $body")
    }

    private fun mapNetworkError(e: Throwable): Nothing {
        throw when (e) {
            is ConnectException -> IOException("无法连接 PC，请检查是否在同一网络")
            is SocketTimeoutException -> IOException("连接超时，请检查 PC 端是否运行")
            else -> e
        }
    }

    companion object {
        fun getFileName(resolver: ContentResolver, uri: Uri): String? {
            if (uri.scheme == "content") {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            }
            return uri.lastPathSegment
        }
    }
}

/** PC 推送目录中的文件信息 */
data class PushFile(val name: String, val sizeBytes: Long) {
    val displaySize: String get() = when {
        sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0)
        sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
        else -> "$sizeBytes B"
    }
    /** 只取最后一段文件名，去掉子目录前缀 */
    val displayName: String get() = name.substringAfterLast('/')
}
