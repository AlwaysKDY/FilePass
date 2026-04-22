package com.filepass.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * PC 端 API 客户端 — 文本推送 & 文件上传。
 * 所有方法均为 suspend 函数，在 IO 调度器上执行。
 */
class ApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val client = OkHttpClient.Builder()
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
                .addHeader("Authorization", "Bearer $token")
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
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string())
                val respJson = JSONObject(resp.body!!.string())
                respJson.getString("filename")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 检测 PC 端是否在线 */
    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/ping")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, null)
                val respJson = JSONObject(resp.body!!.string())
                respJson.getString("name")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    /** 获取 PC 剪贴板内容 */
    suspend fun getClipboard(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/clipboard")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, null)
                val respJson = JSONObject(resp.body!!.string())
                respJson.getString("content")
            }
        }.recoverCatching { mapNetworkError(it) }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun httpError(code: Int, body: String?): IOException {
        val msg = when (code) {
            401 -> "认证失败，请检查 Token"
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
