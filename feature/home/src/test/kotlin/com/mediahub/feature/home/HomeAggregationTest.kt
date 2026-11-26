package com.mediahub.feature.home

import androidx.lifecycle.ViewModelStore
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首页聚合（条目 42/117/121/144/148）：
 * 分区失败隔离、临时首页、线路名称选择器、单资源继续观看与空状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAggregationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeRegistry : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): com.mediahub.provider.api.ProviderHandle? = null
        override fun descriptors(): List<com.mediahub.provider.api.ProviderDescriptor> = emptyList()
    }

    private class FakeServerStore(
        var servers: List<MediaServer>,
        private val observed: Flow<List<MediaServer>>? = null,
    ) : ServerStore {
        private val delegate = kotlinx.coroutines.flow.MutableStateFlow(servers)
        override fun observeServers(): Flow<List<MediaServer>> = observed ?: delegate
        override suspend fun getServer(id: String): MediaServer? = servers.firstOrNull { it.id == id }
        override suspend fun updateServer(server: MediaServer) = Unit
        override suspend fun setDefault(id: String) = Unit
    }

    private class FakeProgressStore(
        private val items: List<PlaybackProgress> = emptyList(),
        private val override: Flow<List<PlaybackProgress>>? = null,
    ) : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> =
            override ?: flowOf(items)
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun server(id: String, name: String, isDefault: Boolean = false, line: String? = null): MediaServer {
        val url = "https://$id.example/media"
        return MediaServer(
            id = id,
            name = name,
            type = ServerType.EMBY,
            isDefault = isDefault,
            createdAtEpochMs = 1L,
            endpoints = line?.let {
                listOf(
                    ServerEndpoint(
                        id = "${id}_ep",
                        serverId = id,
                        name = it,
                        url = url,
                        isPrimary = true,
                        enabled = true,
                    ),
                )
            }.orEmpty(),
        )
    }

    private fun progress(serverId: String, itemId: String) = PlaybackProgress(
        serverId = serverId,
        itemId = itemId,
        positionMs = 10,
        durationMs = 100,
        isPaused = true,
        updatedAtEpochMs = 1L,
        itemTitle = itemId,
    )

    // ---- 144：数据源选择器显示线路名称，不显示地址 / ID ----

    @Test
    fun `source selector labels use line name instead of address or id`() {
        val options = homeSourceOptions(
            listOf(
                server("srv-a", "家庭影院", isDefault = true, line = "主线路"),
                server("srv-b", "备用机房", line = "备用线路"),
                MediaServer("local", "本地影片", ServerType.LOCAL, createdAtEpochMs = 1L),
            ),
        )

        assertEquals(listOf("主线路", "备用线路", "本地影片"), options.map { it.label })
        options.forEach { option ->
            assertFalse("不得展示服务器地址：${option.label}", option.label.contains("example"))
            assertFalse("不得展示服务器 ID：${option.label}", option.label == option.serverId)
        }
    }

    @Test
    fun `source selector falls back to source name when line name is blank`() {
        assertEquals("家庭影院", server("srv-a", "家庭影院", line = "  ").homeSourceLabel())
    }

    @Test
    fun `same line name on different sources is disambiguated by source name`() {
        val options = homeSourceOptions(
            listOf(
                server("srv-a", "家庭影院", line = "默认线路"),
                server("srv-b", "备用机房", line = "默认线路"),
            ),
        )
        assertEquals(listOf("默认线路 · 家庭影院", "默认线路 · 备用机房"), options.map { it.label })
    }

    // ---- 117：默认首页复用 isDefault，临时切换只存在内存 ----

    @Test
    fun `default home source prefers persisted default then first source`() {
        assertEquals(
            "srv-b",
            defaultHomeSourceId(listOf(server("srv-a", "A"), server("srv-b", "B", isDefault = true))),
        )
        assertEquals("srv-a", defaultHomeSourceId(listOf(server("srv-a", "A"), server("srv-b", "B"))))
        assertNull(defaultHomeSourceId(emptyList()))
    }

    @Test
    fun `temporary home switch is memory only and restarts at the persisted default`() = runTest(dispatcher) {
        val store = FakeServerStore(
            listOf(server("srv-a", "A", isDefault = true), server("srv-b", "B")),
        )
        val first = HomeViewModel(store, FakeProgressStore(), FakeRegistry(), noOpLogger)
        val firstOwner = ViewModelStore().apply { put("home", first) }
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            jobs += backgroundScope.launch { first.homeServerId.collect {} }
            jobs += backgroundScope.launch { first.isTemporaryHome.collect {} }
            jobs += backgroundScope.launch { first.defaultHomeServerId.collect {} }
            advanceUntilIdle()
            assertEquals("srv-a", first.homeServerId.value)
            assertFalse(first.isTemporaryHome.value)

            first.setAsHome("srv-b")
            advanceUntilIdle()
            assertEquals("srv-b", first.homeServerId.value)
            assertTrue(first.isTemporaryHome.value)
            assertEquals("临时切换不得改写持久默认", "srv-a", first.defaultHomeServerId.value)
        } finally {
            firstOwner.clear()
            jobs.forEach { it.cancel() }
            advanceUntilIdle()
        }

        val second = HomeViewModel(store, FakeProgressStore(), FakeRegistry(), noOpLogger)
        val secondOwner = ViewModelStore().apply { put("home", second) }
        val secondJob = backgroundScope.launch { second.homeServerId.collect {} }
        val secondTemporaryJob = backgroundScope.launch { second.isTemporaryHome.collect {} }
        try {
            advanceUntilIdle()
            assertEquals("重启后恢复默认首页", "srv-a", second.homeServerId.value)
            assertFalse(second.isTemporaryHome.value)
        } finally {
            secondJob.cancel()
            secondTemporaryJob.cancel()
            secondOwner.clear()
            advanceUntilIdle()
        }
    }

    @Test
    fun `restore default home clears the temporary selection`() = runTest(dispatcher) {
        val store = FakeServerStore(listOf(server("srv-a", "A", isDefault = true), server("srv-b", "B")))
        val vm = HomeViewModel(store, FakeProgressStore(), FakeRegistry(), noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        val job = backgroundScope.launch { vm.homeServerId.collect {} }
        try {
            advanceUntilIdle()
            vm.setAsHome("srv-b")
            advanceUntilIdle()
            assertEquals("srv-b", vm.homeServerId.value)

            vm.restoreDefaultHome()
            advanceUntilIdle()
            assertEquals("srv-a", vm.homeServerId.value)
            assertFalse(vm.isTemporaryHome.value)
        } finally {
            job.cancel()
            owner.clear()
            advanceUntilIdle()
        }
    }

    // ---- 121/148：单资源继续观看范围与空状态 ----

    @Test
    fun `continue watching is scoped to the selected source`() {
        val all = listOf(progress("srv-a", "a1"), progress("srv-b", "b1"), progress("srv-a", "a2"))
        assertEquals(listOf("a1", "a2"), scopedContinueWatching(all, "srv-a").map { it.itemId })
        assertEquals(all, scopedContinueWatching(all, null))
    }

    @Test
    fun `empty resource aggregation asks for playable resources explicitly`() {
        assertTrue(shouldShowNoPlayableResources(listOf(server("srv-a", "A")), emptyList()))
        assertFalse(shouldShowNoPlayableResources(listOf(server("srv-a", "A")), listOf(progress("srv-a", "a1"))))
        assertFalse("尚未接入来源时保留引导空态", shouldShowNoPlayableResources(emptyList(), emptyList()))
        assertEquals("暂无可播放资源", NO_PLAYABLE_RESOURCES)
    }

    // ---- 42：单个分区失败不得连带整页 ----

    @Test
    fun `continue watching failure keeps the server section alive`() = runTest(dispatcher) {
        val store = FakeServerStore(listOf(server("srv-a", "A")))
        val brokenProgress = FakeProgressStore(
            override = flow { throw IllegalStateException("progress decode failed") },
        )
        val vm = HomeViewModel(store, brokenProgress, FakeRegistry(), noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        val serversJob = backgroundScope.launch { vm.servers.collect {} }
        val progressJob = backgroundScope.launch { vm.continueWatching.collect {} }
        try {
            advanceUntilIdle()

            assertEquals(listOf("srv-a"), vm.servers.value.map { it.id })
            assertFalse("媒体源分区本身正常", vm.serversFailed.value)
            assertTrue("继续观看分区应单独标记失败", vm.continueWatchingFailed.value)
            assertTrue(vm.continueWatching.value.isEmpty())
        } finally {
            serversJob.cancel()
            progressJob.cancel()
            owner.clear()
            advanceUntilIdle()
        }
    }

    @Test
    fun `server section failure keeps continue watching alive`() = runTest(dispatcher) {
        val store = FakeServerStore(
            listOf(server("srv-a", "A")),
            observed = flow { throw IllegalStateException("server table corrupt") },
        )
        val vm = HomeViewModel(store, FakeProgressStore(listOf(progress("srv-a", "a1"))), FakeRegistry(), noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        val serversJob = backgroundScope.launch { vm.servers.collect {} }
        val progressJob = backgroundScope.launch { vm.continueWatching.collect {} }
        try {
            advanceUntilIdle()

            assertTrue("媒体源分区应单独标记失败", vm.serversFailed.value)
            assertTrue("失败后不得一直转圈", vm.loaded.value)
            assertEquals(listOf("a1"), vm.continueWatching.value.map { it.itemId })
            assertFalse("继续观看分区本身正常", vm.continueWatchingFailed.value)
        } finally {
            serversJob.cancel()
            progressJob.cancel()
            owner.clear()
            advanceUntilIdle()
        }
    }
}
