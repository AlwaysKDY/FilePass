package com.filepass.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppConfigTest {

    private lateinit var config: AppConfig
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = AppConfig(context)
        config.clear()
    }

    @After
    fun tearDown() {
        config.clear()
    }

    @Test
    fun `default values are correct`() {
        assertEquals("", config.pcHost)
        assertEquals(8765, config.pcPort)
        assertEquals("", config.token)
        assertFalse(config.isConnected)
        assertFalse(config.isConfigured)
        assertEquals("", config.baseUrl)
    }

    @Test
    fun `set and get pcHost`() {
        config.pcHost = "192.168.1.100"
        assertEquals("192.168.1.100", config.pcHost)
    }

    @Test
    fun `set and get pcPort`() {
        config.pcPort = 9999
        assertEquals(9999, config.pcPort)
    }

    @Test
    fun `set and get token`() {
        config.token = "my-secret-token"
        assertEquals("my-secret-token", config.token)
    }

    @Test
    fun `isConfigured requires both host and token`() {
        assertFalse(config.isConfigured)

        config.pcHost = "192.168.1.1"
        assertFalse(config.isConfigured) // token still empty

        config.token = "abc"
        assertTrue(config.isConfigured) // both set

        config.pcHost = ""
        assertFalse(config.isConfigured) // host cleared
    }

    @Test
    fun `baseUrl is correctly formed`() {
        config.pcHost = "10.0.0.5"
        config.pcPort = 8765
        assertEquals("http://10.0.0.5:8765", config.baseUrl)
    }

    @Test
    fun `baseUrl empty when host not set`() {
        assertEquals("", config.baseUrl)
    }

    @Test
    fun `isConnected persists`() {
        config.isConnected = true
        assertTrue(config.isConnected)
        config.isConnected = false
        assertFalse(config.isConnected)
    }

    @Test
    fun `clear resets all values`() {
        config.pcHost = "1.2.3.4"
        config.token = "tok"
        config.pcPort = 1234
        config.isConnected = true

        config.clear()

        assertEquals("", config.pcHost)
        assertEquals(8765, config.pcPort) // defaults back
        assertEquals("", config.token)
        assertFalse(config.isConnected)
    }

    @Test
    fun `persistence across instances`() {
        config.pcHost = "192.168.0.50"
        config.token = "persist-token"

        // Create a new instance pointing to same SharedPreferences
        val config2 = AppConfig(context)
        assertEquals("192.168.0.50", config2.pcHost)
        assertEquals("persist-token", config2.token)
    }
}
