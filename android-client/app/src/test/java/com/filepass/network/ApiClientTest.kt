package com.filepass.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        apiClient = ApiClient(baseUrl)
    }

    @After
    fun tearDown() {
        apiClient.shutdown()
        server.shutdown()
    }

    // ── sendText ──

    @Test
    fun `sendText success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","length":5}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = apiClient.sendText("hello")
        assertTrue(result.isSuccess)
        assertEquals(5, result.getOrNull())

        // 验证请求格式
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/text", request.path)
        assertTrue(request.body.readUtf8().contains("\"content\":\"hello\""))
    }

    @Test
    fun `sendText server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = apiClient.sendText("fail")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `sendText auth failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"detail":"server error"}""")
        )

        val result = apiClient.sendText("error")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `sendText chinese text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","length":6}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = apiClient.sendText("你好世界测试中")
        assertTrue(result.isSuccess)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("你好世界测试中"))
    }

    // ── ping ──

    @Test
    fun `ping success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","name":"MY-PC","version":"1.0.0"}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = apiClient.ping()
        assertTrue(result.isSuccess)
        assertEquals("MY-PC", result.getOrNull())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/ping", request.path)
    }

    @Test
    fun `ping server offline`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = apiClient.ping()
        assertTrue(result.isFailure)
    }

    // ── getClipboard ──

    @Test
    fun `getClipboard success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":"PC端剪贴板内容"}""")
                .addHeader("Content-Type", "application/json")
        )

        val result = apiClient.getClipboard()
        assertTrue(result.isSuccess)
        assertEquals("PC端剪贴板内容", result.getOrNull())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/clipboard", request.path)
    }

    @Test
    fun `getClipboard unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.getClipboard()
        assertTrue(result.isFailure)
    }

    // ── 请求格式验证 ──

    @Test
    fun `content type is json for sendText`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"ok","length":1}""")
        )

        apiClient.sendText("x")
        val request = server.takeRequest()
        assertTrue(
            request.getHeader("Content-Type")
                ?.contains("application/json") == true
        )
    }
}
