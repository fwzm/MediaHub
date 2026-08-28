package com.mediahub.feature.search

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.feature.search.engine.GlobalSearchEngine
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 聚合搜索 ViewModel（Phase 1C-1）：走真实 GlobalSearchEngine + fake 数据源，
 * 验证去抖 / 能力过滤 / 旧 query 取消 / partial success 透传。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val serverA = MediaServer(
        id = "srv-a", name = "予初", type = ServerType.EMBY,
        baseUrl = "https://a.example.com", createdAtEpochMs = 0L,
    )
    private val serverB = MediaServer(
        id = "srv-b", name = "墨云阁", type = ServerType.EMBY,
        baseUrl = "https://b.example.com", createdAtEpochMs = 0L,
    )

    private class FakeLogger : Logger {
        override fun d(tag: LogTag, message: String) {}
        override fun i(tag: LogTag, message: String) {}
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {}
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {}
    }

    private class FakeServerStore(private val servers: List<MediaServer>) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flow { emit(servers) }
        override suspend fun getServer(id: String): MediaServer? = servers.firstOrNull { it.id == id }
    }

    /** 可编程 fake：记录 query、按 behavior 行事。 */
    private class FakeSearch(
        private val behavior: suspend (String, PageRequest) -> PagedResult<MediaItem> = { _, _ ->
            PagedResult(emptyList(), totalCount = 0, hasMore = false)
        },
    ) : MediaSearchProvider {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> {
            queries += query
            return behavior(query, page)
        }
    }

    private fun handle(search: MediaSearchProvider?): ProviderHandle {
        val provider = object : MediaProvider {
            override val serverId = "x"
            override val type = ServerType.EMBY
            override val displayName = "x"
            override val descriptor = ProviderDescriptor(
                id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
                category = ProviderCategory.MEDIA_SERVER,
                declaredCapabilities = emptySet(),
                authMethod = AuthMethod.USERNAME_PASSWORD,
                status = ProviderStatus.EXPERIMENTAL,
            )
            override suspend fun testConnection() = ConnectionStatus(ok = true, message = "ok")
        }
        return ProviderHandle(provider = provider, search = search)
    }

    private fun item(serverId: String, id: String, title: String) = MediaItem(
        serverId = serverId, id = id, type = MediaType.MOVIE, title = title,
    )

    private fun viewModel(
        servers: List<MediaServer>,
        handles: Map<String, ProviderHandle?>,
        engine: GlobalSearchEngine = GlobalSearchEngine(perServerTimeoutMs = 2_000),
    ) = GlobalSearchViewModel(FakeServerStore(servers), FakeRegistry(handles), engine, FakeLogger())

    private class FakeRegistry(
        private val handles: Map<String, ProviderHandle?>,
    ) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override fun create(server: MediaServer): ProviderHandle? = handles[server.id]
        override val supportedTypes: Set<ServerType> = setOf(ServerType.EMBY)
        override fun descriptors() = emptyList<ProviderDescriptor>()
    }

    // ---- 去抖：连续输入只触发最后一次 ----

    @Test
    fun `debounced input triggers single search with final query`() = runTest {
        val searchA = FakeSearch()
        val vm = viewModel(listOf(serverA), mapOf("srv-a" to handle(searchA)))

        val job = launch { vm.state.collect {} }
        runCurrent()

        vm.onQueryChange("冰")
        advanceTimeBy(100)
        vm.onQueryChange("冰血")
        advanceTimeBy(100)
        vm.onQueryChange("冰血暴")
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("冰血暴"), searchA.queries)
        job.cancel()
    }

    // ---- 空白 query：不发搜索 ----

    @Test
    fun `blank query never invokes search`() = runTest {
        val searchA = FakeSearch()
        val vm = viewModel(listOf(serverA), mapOf("srv-a" to handle(searchA)))

        val job = launch { vm.state.collect {} }
        runCurrent()
        vm.onQueryChange("   ")
        advanceTimeBy(500)
        runCurrent()

        assertEquals(0, searchA.queries.size)
        job.cancel()
    }

    // ---- 能力过滤：只有具备 SEARCH 能力的服务器参与 ----

    @Test
    fun `targets filtered to servers with search capability`() = runTest {
        val searchA = FakeSearch()
        val searchB = FakeSearch()
        val vm = viewModel(
            listOf(serverA, serverB),
            mapOf("srv-a" to handle(searchA), "srv-b" to handle(null)), // srv-b 无 SEARCH 能力
        )

        val job = launch { vm.state.collect {} }
        runCurrent()
        vm.onQueryChange("fargo")
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("fargo"), searchA.queries)
        assertEquals(0, searchB.queries.size)
        job.cancel()
    }

    // ---- 旧 query 在途搜索被取消（flatMapLatest 切换 → 引擎在途 lambda 收到取消） ----

    @Test
    fun `switching query cancels previous in flight search`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        var firstCancelled = false
        val hanging = FakeSearch { _, _ ->
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled = true
            }
        }
        val second = FakeSearch()
        val vm = viewModel(
            listOf(serverA, serverB),
            mapOf("srv-a" to handle(hanging), "srv-b" to handle(second)),
        )

        val job = launch { vm.state.collect {} }
        runCurrent()
        vm.onQueryChange("旧查询")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        vm.onQueryChange("新查询")
        advanceTimeBy(400)
        runCurrent()

        assertTrue("旧查询的在途搜索必须被取消", firstCancelled)
        // 新查询重新搜索（两台都会被再次调用）
        assertTrue(second.queries.contains("新查询"))
        job.cancel()
    }

    // ---- Partial success：部分服务器失败不影响其它结果进入 state ----

    @Test
    fun `partial success surfaces hits and errors together`() = runTest {
        val ok = FakeSearch { _, page ->
            PagedResult(items = listOf(item("srv-a", "m1", "冰血暴")), totalCount = 1, hasMore = false)
        }
        val failing = FakeSearch { _, _ -> throw IOException("wire down") }
        val vm = viewModel(
            listOf(serverA, serverB),
            mapOf("srv-a" to handle(ok), "srv-b" to handle(failing)),
        )

        val collected = mutableListOf<GlobalSearchState>()
        val job = launch { vm.state.collect { collected += it } }
        runCurrent()
        vm.onQueryChange("冰血暴")
        advanceTimeBy(9_000) // 超过引擎 2s 超时
        runCurrent()

        val final = collected.last()
        assertEquals(1, final.hits.size)
        assertEquals("予初", final.hits[0].serverName)
        assertTrue(final.errors.containsKey("srv-b"))
        assertFalse(final.isSearching)
        job.cancel()
    }
}
