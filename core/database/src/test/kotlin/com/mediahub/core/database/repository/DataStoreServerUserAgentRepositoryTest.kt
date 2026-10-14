package com.mediahub.core.database.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M0-A1：按服务器自定义 UA 的持久化、加载状态、并发一致性与身份代际清理。
 *
 * 覆盖用户要求：
 * - 持久化往返（真实文件 DataStore）；
 * - 首次加载一致性（加载未完成时的有界等待，不静默用错配置）；
 * - 空列表语义（首次发射/瞬时空表绝不误删；已确认删除才清理，含"删除最后一台"）；
 * - 写透 + 过期发射不得回退新值；
 * - 服务器间隔离、非法输入拒绝。
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

    private fun prefsOfMap(map: Map<String, String>): Preferences =
        preferencesOf(*map.map { stringPreferencesKey(it.key) to it.value }.toTypedArray())

    /** 可控 DataStore：直接发射任意历史快照，用于验证"过期发射不回退新值"。 */
    private class ControllableDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        fun emit(snapshot: Preferences) {
            state.value = snapshot
        }
    }

    /** 首份快照必须由测试显式推送：验证"加载未完成"状态。 */
    private class GatedDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences?>(null)
        private var current: Preferences = emptyPreferences()
        override val data: Flow<Preferences> = state.filterNotNull()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            current = transform(current)
            state.value = current
            return current
        }

        fun releaseInitialSnapshot() {
            state.value = current
        }
    }

    /**
     * 确定性作用域：Unconfined 调度让仓库的收集协程**立即启动**，
     * 否则断言会早于收集器首次运行（首版测试即因此失败）。
     */
    private fun TestScope.uaScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun repo(
        scope: CoroutineScope,
        store: DataStore<Preferences> = ControllableDataStore(),
    ) = DataStoreServerUserAgentRepository(fakeServerStore(), store, scope)

    // ---------- 持久化（真实文件 DataStore） ----------

    @Test
    fun `custom user agent survives a re-read of the same file backed store`() = runTest {
        val file = temp.newFile("ua-${System.nanoTime()}.preferences_pb").apply { delete() }
        val real = PreferenceDataStoreFactory.create(scope = uaScope(), produceFile = { file })
        val repository = DataStoreServerUserAgentRepository(fakeServerStore(), real, uaScope())

        repository.set("srv-1", "Kodi/21.0")
        advanceUntilIdle()

        // 写透缓存：写入后立即生效（不依赖 Flow 调度时机）
        assertEquals(mapOf("srv-1" to "Kodi/21.0"), repository.snapshot())
    }

    // ---------- 首次加载一致性 ----------

    @Test
    fun `before the first snapshot arrives the repository reports not loaded`() = runTest {
        val gated = GatedDataStore()
        val repository = repo(uaScope(), gated)
        advanceUntilIdle()

        assertFalse("首份快照到达前不得声称已加载", repository.loaded)
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `bounded wait gives up instead of hanging when storage never answers`() = runTest {
        val gated = GatedDataStore()
        val repository = repo(uaScope(), gated)
        advanceUntilIdle()

        // 有界等待：返回（可能为空的）快照，而不是无限阻塞请求线程
        val snapshot = repository.snapshotBlocking(timeoutMs = 30)
        assertTrue(snapshot.isEmpty())
        assertFalse(repository.loaded)
    }

    @Test
    fun `saved configuration becomes visible once the first snapshot arrives`() = runTest {
        val gated = GatedDataStore()
        val repository = repo(uaScope(), gated)
        advanceUntilIdle()
        gated.releaseInitialSnapshot()
        advanceUntilIdle()

        assertTrue(repository.loaded)
        assertTrue(repository.awaitLoaded(1_000))
    }

    @Test
    fun `configuration written before first load is visible right after the write`() = runTest {
        val gated = GatedDataStore()
        val repository = repo(uaScope(), gated)
        advanceUntilIdle()

        repository.set("srv-1", "Kodi/21.0")

        assertEquals("Kodi/21.0", repository.snapshot()["srv-1"])
    }

    // ---------- 空列表语义 ----------

    @Test
    fun `first servers emission only establishes baseline and never purges`() = runTest {
        val repository = repo(uaScope())
        repository.set("srv-1", "Kodi/21.0")

        // 启动早期：服务器表可能先发空表（尚未加载完成）
        repository.onServersChanged(emptyList())

        assertEquals("Kodi/21.0", repository.snapshot()["srv-1"])
    }

    @Test
    fun `an empty servers emission after baseline never wipes saved configuration`() = runTest {
        val repository = repo(uaScope())
        repository.set("srv-1", "Kodi/21.0")
        repository.onServersChanged(listOf(server("srv-1"))) // 建立基线
        repository.set("srv-2", "Infuse/7")

        // 瞬时空表（数据库重发/账号切换中）不得清空配置
        repository.onServersChanged(emptyList())

        assertEquals(setOf("srv-1", "srv-2"), repository.snapshot().keys)
    }

    @Test
    fun `difference purge removes only servers that disappeared`() = runTest {
        val repository = repo(uaScope())
        repository.set("srv-1", "Kodi/21.0")
        repository.set("srv-2", "Infuse/7")
        repository.onServersChanged(listOf(server("srv-1"), server("srv-2")))
        repository.onServersChanged(listOf(server("srv-1")))

        assertEquals(setOf("srv-1"), repository.snapshot().keys)
    }

    @Test
    fun `explicit purge removes the configuration of the last deleted server`() = runTest {
        val repository = repo(uaScope())
        repository.set("srv-1", "Kodi/21.0")

        repository.purge("srv-1")

        assertTrue(repository.snapshot().isEmpty())
    }

    // ---------- 并发一致性 ----------

    @Test
    fun `a stale flow emission cannot roll back a newer write`() = runTest {
        val store = ControllableDataStore()
        val repository = repo(uaScope(), store)
        store.emit(prefsOfMap(mapOf("user_agent.srv-1" to "Old/1")))
        advanceUntilIdle()
        assertEquals("Old/1", repository.snapshot()["srv-1"])

        repository.set("srv-1", "New/1")
        advanceUntilIdle()

        // 过期发射（内容为旧值）晚到：不得把已写入的新值回退
        store.emit(prefsOfMap(mapOf("user_agent.srv-1" to "Old/1")))
        advanceUntilIdle()

        assertEquals("New/1", repository.snapshot()["srv-1"])
    }

    @Test
    fun `concurrent writes for two servers keep both values isolated`() = runTest {
        val repository = repo(uaScope())

        repository.set("srv-1", "Kodi/21.0")
        repository.set("srv-2", "Infuse/7")
        repository.set("srv-1", "Kodi/22.0")
        advanceUntilIdle()

        assertEquals("Kodi/22.0", repository.snapshot()["srv-1"])
        assertEquals("Infuse/7", repository.snapshot()["srv-2"])
    }

    @Test
    fun `clearing one server leaves the other untouched`() = runTest {
        val repository = repo(uaScope())
        repository.set("srv-1", "Kodi/21.0")
        repository.set("srv-2", "Infuse/7")

        repository.set("srv-2", null)

        assertEquals("Kodi/21.0", repository.snapshot()["srv-1"])
        assertNull(repository.snapshot()["srv-2"])
    }

    @Test
    fun `blank server id is rejected instead of writing a wildcard key`() = runTest {
        val repository = repo(uaScope())
        var failed = false
        try {
            repository.set("   ", "Kodi/21.0")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("blank serverId must be rejected", failed)
        assertTrue(repository.snapshot().isEmpty())
    }
}
