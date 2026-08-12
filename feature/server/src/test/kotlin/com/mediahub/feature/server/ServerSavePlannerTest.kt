package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * existing-server re-login / local reauthorization 的保存决策（评审 Patch 2）。
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
        // 用户重新登录后提交的新候选（新 name/baseUrl，其他靠 planner 保留）
        val candidate = emby("srv-DIFFERENT", name = "新名称", created = 999L)

        val decision = ServerSavePlanner.plan(existing, candidate)

        assertTrue(decision.updateSource)          // 走 updateServer
        assertEquals("srv-1", decision.server.id)  // 复用 SAME id
        assertEquals("新名称", decision.server.name)
        assertTrue(decision.server.isDefault)      // 保留
        assertEquals(3, decision.server.sortOrder) // 保留
        assertEquals(100L, decision.server.createdAtEpochMs) // 保留

        // 关键：绝不能产生第二个 id
        assertNotEquals("srv-DIFFERENT", decision.server.id)
    }

    @Test
    fun `relogin does not increase count since it updates source`() {
        // 规划器本身不增删数；updateSource=true 表示不走向 addServer 路径
        val existing = emby("srv-1")
        val decision = ServerSavePlanner.plan(existing, emby("x"))
        assertTrue(decision.updateSource)
    }

    // ---- LOCAL reauthorization ----

    @Test
    fun `local reauthorization reuses same id and preserves metadata`() {
        val existing = MediaServer(
            id = "local-1",
            name = "家庭NAS",
            providerId = "local",
            baseUrl = "", // SAF uri 失效
            isDefault = true,
            sortOrder = 1,
            createdAtEpochMs = 200L,
        )
        val candidate = MediaServer(
            id = "local-CANDIDATE",
            name = "家庭NAS",
            providerId = "local",
            baseUrl = "content://tree/primary%3AMovies", // 新授权
            createdAtEpochMs = 500L,
        )

        val decision = ServerSavePlanner.plan(existing, candidate)

        assertTrue(decision.updateSource)
        assertEquals("local-1", decision.server.id) // 保留 SAME id
        assertEquals("content://tree/primary%3AMovies", decision.server.baseUrl) // 更新到新目录
        assertEquals(200L, decision.server.createdAtEpochMs) // 保留
        assertTrue(decision.server.isDefault)
    }

    // ---- NEW server（新建模式不受破坏） ----

    @Test
    fun `new server uses addServer path and does not inherit old id`() {
        val candidate = emby("brand-new-1", created = 1L)
        val decision = ServerSavePlanner.plan(existing = null, candidate)

        assertFalse(decision.updateSource) // 走 addServer
        assertEquals("brand-new-1", decision.server.id)
    }
}
