package com.mediahub.feature.home

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 继续观看卡片线路名：只读取 active endpoint 的名称，不做网络或 Compose 依赖。 */
class ContinueWatchingLineTest {

    private fun server(id: String, endpoints: List<ServerEndpoint>) = MediaServer(
        id = id,
        name = "服务器 $id",
        type = ServerType.EMBY,
        baseUrl = "http://localhost",
        createdAtEpochMs = 0,
    ).copy(endpoints = endpoints)

    private fun endpoint(id: String, name: String, primary: Boolean = false, enabled: Boolean = true) =
        ServerEndpoint(
            id = id,
            serverId = "srv",
            name = name,
            url = "http://localhost",
            isPrimary = primary,
            enabled = enabled,
        )

    @Test
    fun `uses the active endpoint name`() {
        val servers = listOf(
            server("srv-1", listOf(endpoint("ep-1", "备用线路"), endpoint("ep-2", "主线路", primary = true))),
        )
        assertEquals("主线路", continueWatchingLineName(servers, "srv-1"))
    }

    @Test
    fun `falls back to the first enabled endpoint when no primary is enabled`() {
        val servers = listOf(
            server(
                "srv-1",
                listOf(
                    endpoint("ep-0", "停用线路", enabled = false),
                    endpoint("ep-1", "可用线路"),
                    endpoint("ep-2", "备用线路"),
                ),
            ),
        )
        assertEquals("可用线路", continueWatchingLineName(servers, "srv-1"))
    }

    @Test
    fun `returns null for missing server or blank line name`() {
        assertNull(continueWatchingLineName(emptyList(), "srv-1"))
        val servers = listOf(server("srv-1", listOf(endpoint("ep-1", "   "))))
        assertNull(continueWatchingLineName(servers, "srv-1"))
    }

    @Test
    fun `returns null when no endpoint is enabled`() {
        val servers = listOf(
            server("srv-1", listOf(endpoint("ep-1", "停用线路", enabled = false))),
        )
        assertNull(continueWatchingLineName(servers, "srv-1"))
    }
}
