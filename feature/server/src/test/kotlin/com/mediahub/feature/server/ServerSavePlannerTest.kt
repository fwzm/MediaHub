package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * existing-server re-login / local reauthorization 的保存决策。
 * 核心约束：复用 same id、保留元数据、走 update（不 addServer 不重复）、新建模式不受破坏。
 */
class ServerSavePlannerTest {

    private fun emby(id: String, name: String = "我的Emby", created: Long = 100L) = MediaServer(
        id = id,
        name = name,
        providerId = "emby",
        baseUrl = "http://192.168.1.10:8096",
        username = "alice",
        createdAtEpochMs = created,
    )

    // ---- AUTH existing re-login ----

    @Test
    fun `relogin reuses same id and preserves metadata`() {
        val existing = emby("srv-1", created = 100L).copy(
            isDefault = true,
            sortOrder = 3,
            lastError = "旧错误",
        )
        val candidate = emby("srv-DIFFERENT", name = "新名称", created = 999L)

        val decision = ServerSavePlanner.plan(existing, candidate)

        assertTrue(decision.updateSource)          // 走 updateServer
        assertEquals("srv-1", decision.server.id)  // 复用 SAME id
        assertEquals("新名称", decision.server.name)
        assertTrue(decision.server.isDefault)      // 保留
        assertEquals(3, decision.server.sortOrder) // 保留
        assertEquals(100L, decision.server.createdAtEpochMs) // 保留
        assertNotEquals("srv-DIFFERENT", decision.server.id)
    }

    @Test
    fun `relogin updates source so repository count stays the same`() {
        // 依据决策驱动持久化：update 语义不新增记录，count 不变
        var repositoryCount = 1
        val existing = emby("srv-1")
        val decision = ServerSavePlanner.plan(existing, emby("x"))

        if (!decision.updateSource) repositoryCount += 1 // addServer 才会 +1
        assertEquals(1, repositoryCount)
        assertEquals("srv-1", decision.server.id)
    }

    @Test
    fun `new server adds and increases count`() {
        var repositoryCount = 1
        val decision = ServerSavePlanner.plan(null, emby("srv-new"))

        if (!decision.updateSource) repositoryCount += 1 // addServer 语义
        assertEquals(2, repositoryCount)
        assertEquals("srv-new", decision.server.id)
    }

    // ---- LOCAL reauthorization ----

    @Test
    fun `local reauthorization reuses same id and preserves metadata`() {
        val existing = MediaServer(
            id = "local-1",
            name = "家庭NAS",
            providerId = "local",
            baseUrl = "",
            isDefault = true,
            sortOrder = 1,
            createdAtEpochMs = 200L,
        )
        val candidate = MediaServer(
            id = "local-CANDIDATE",
            name = "家庭NAS",
            providerId = "local",
            baseUrl = "content://tree/primary%3AMovies",
            createdAtEpochMs = 500L,
        )

        val decision = ServerSavePlanner.plan(existing, candidate)

        assertTrue(decision.updateSource)
        assertEquals("local-1", decision.server.id)
        assertEquals("content://tree/primary%3AMovies", decision.server.baseUrl)
        assertEquals(200L, decision.server.createdAtEpochMs)
        assertTrue(decision.server.isDefault)
    }

    // ---- NEW server（新建模式不受破坏） ----

    @Test
    fun `new server uses addServer path and does not inherit old id`() {
        val candidate = emby("brand-new-1", created = 1L)
        val decision = ServerSavePlanner.plan(existing = null, candidate)

        assertFalse(decision.updateSource)
        assertEquals("brand-new-1", decision.server.id)
    }
}
