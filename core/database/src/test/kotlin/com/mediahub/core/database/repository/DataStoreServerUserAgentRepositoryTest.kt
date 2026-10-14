package com.mediahub.core.database.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M0-A 闭环（ADR-045）：按服务器自定义 UA 的持久化、隔离与身份代际清理。
 *
 * 覆盖用户要求中的：持久化读取 / 不同 serverId 隔离 / 删除服务器后清理 /
 * 恢复后身份变化（孤儿清理）/ 快照可被同步读取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreServerUserAgentRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val servers = MutableStateFlow<List<MediaServer>>(emptyList())

    private fun fakeServerStore() = object : ServerStore {
        override fun observeServers() = servers
        override suspend fun getServer(id: String) = servers.value.firstOrNull { it.id == id }
    }

    private fun server(id: String) =
        MediaServer(id = id, name = id, type = ServerType.EMBY, createdAtEpochMs = 0L)

    private fun repository(scope: CoroutineScope): DataStoreServerUserAgentRepository {
        val file = temp.newFile("ua-${System.nanoTime()}.preferences_pb").apply { delete() }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return DataStoreServerUserAgentRepository(fakeServerStore(), dataStore, scope)
    }

    @Test
    fun `custom user agent survives a re-read of the same store`() = runTest {
        val repo = repository(backgroundScope)
        repo.set("srv-1", "Kodi/21.0")
        advanceUntilIdle()

        assertEquals(mapOf("srv-1" to "Kodi/21.0"), repo.snapshot())
    }

    @Test
    fun `blank value clears back to default`() = runTest {
        val repo = repository(backgroundScope)
        repo.set("srv-1", "Kodi/21.0")
        advanceUntilIdle()
        repo.set("srv-1", "   ")
        advanceUntilIdle()

        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `two servers keep isolated user agents`() = runTest {
        val repo = repository(backgroundScope)
        repo.set("srv-1", "Kodi/21.0")
        repo.set("srv-2", "Infuse/7")
        advanceUntilIdle()

        assertEquals("Kodi/21.0", repo.snapshot()["srv-1"])
        assertEquals("Infuse/7", repo.snapshot()["srv-2"])
    }

    @Test
    fun `purge removes only configuration of servers that no longer exist`() = runTest {
        val repo = repository(backgroundScope)
        repo.set("srv-1", "Kodi/21.0")
        repo.set("srv-2", "Infuse/7")
        advanceUntilIdle()

        val removed = repo.purgeOrphans(setOf("srv-1"))
        advanceUntilIdle()

        assertEquals(1, removed)
        assertEquals(setOf("srv-1"), repo.snapshot().keys)
    }

    @Test
    fun `server deletion purges its user agent through the servers-changed path`() = runTest {
        val repo = repository(backgroundScope)
        repo.set("srv-1", "Kodi/21.0")
        repo.set("srv-2", "Infuse/7")
        assertEquals(2, repo.snapshot().size)

        // 删除 srv-2（账号/服务器被移除，或备份恢复后身份变化）
        repo.onServersChanged(listOf(server("srv-1")))

        assertEquals(setOf("srv-1"), repo.snapshot().keys)
        assertFalse(repo.snapshot().containsKey("srv-2"))
    }

    @Test
    fun `blank server id is rejected instead of writing a wildcard key`() = runTest {
        val repo = repository(backgroundScope)
        var failed = false
        try {
            repo.set("   ", "Kodi/21.0")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("blank serverId must be rejected", failed)
        assertTrue(repo.snapshot().isEmpty())
    }
}
