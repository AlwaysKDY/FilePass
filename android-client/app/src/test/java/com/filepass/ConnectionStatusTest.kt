package com.filepass

import com.filepass.config.AppConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * 测试 ConnectionStatus 状态机逻辑。
 */
class ConnectionStatusTest {

    @Test
    fun `Unknown status is initial state`() {
        val status: ConnectionStatus = ConnectionStatus.Unknown
        assertTrue(status is ConnectionStatus.Unknown)
    }

    @Test
    fun `Connected holds PC name`() {
        val status = ConnectionStatus.Connected("MY-DESKTOP")
        assertEquals("MY-DESKTOP", status.pcName)
    }

    @Test
    fun `Failed holds reason`() {
        val status = ConnectionStatus.Failed("Connection refused")
        assertEquals("Connection refused", status.reason)
    }

    @Test
    fun `all status types are distinct`() {
        val statuses = listOf(
            ConnectionStatus.Unknown,
            ConnectionStatus.Searching,
            ConnectionStatus.Testing,
            ConnectionStatus.Connected("PC"),
            ConnectionStatus.Failed("err"),
        )
        assertEquals(5, statuses.map { it::class }.distinct().size)
    }
}
