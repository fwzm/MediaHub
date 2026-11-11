package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.Episode
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaFilter
import com.mediahub.model.MediaFilterField
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaListQuery
import com.mediahub.model.MediaQueryCapabilities
import com.mediahub.model.MediaSortField
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.MediaQueryLibraryProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Library 筛选（Phase 1D）状态测试。
 * 核心：filter 是 container-scoped 状态——进子容器重置、回父容器恢复；
 * 与排序组合后 loadMore 沿用同一快照；race guard 不因 filter 维度增加而失效。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFilterViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private val server = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY, baseUrl = "http://h", createdAtEpochMs = 0,
    )

    private class FakeServerStore(private val target: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(target))
        override suspend fun getServer(id: String): MediaServer? = if (id == target.id) target else null
    }

    private class FakeProvider : MediaProvider {
        override val serverId = "srv-1"
        override val type = ServerType.EMBY
        override val displayName = "Emby"
        override val descriptor = ProviderDescriptor(
            id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.LIBRARY, ProviderCapability.QUERY),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection() = com.mediahub.provider.api.ConnectionStatus(ok = true)
    }

    /** 承载 LIBRARY+QUERY 的 fake；记录每次 query 的 filter/sort/offset，可按 offset 拖延。 */
    private class FakeQueryLibrary(
        override val capabilities: MediaQueryCapabilities =
            MediaQueryCapabilities(
                sortFields = MediaSortField.entries.toSet(),
                filterFields = MediaFilterField.entries.toSet(),
            ),
        private val stallOffset: Int? = null,
        private val stallLibraryId: String? = null,
        private val failFilter: MediaFilter? = null,
    ) : MediaLibraryProvider, MediaQueryLibraryProvider {
        val requests = mutableListOf<MediaListQuery>()

        override suspend fun getLibraries(): List<MediaLibrary> = emptyList()
        override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
        override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()

        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
            getItems(libraryId, MediaListQuery(page = page))

        override suspend fun getItems(libraryId: String, query: MediaListQuery): PagedResult<MediaItem> {
            requests += query
            if (query.filter == failFilter) error("server rejected filter ${query.filter}")
            if (query.page.offset == stallOffset && libraryId == stallLibraryId) {
                // non-cooperative（评审 P2）：吞掉取消并照样返回旧数据——
                // 模拟不响应取消的真实服务器；generation guard 必须丢弃这条晚到的 stale response
                try {
                    delay(200)
                } catch (_: kotlinx.coroutines.CancellationException) {
                }
            }
            val tag = filterTag(query.filter)
            val items = if (query.page.offset == 0) {
                (1..5).map { MediaItem("srv-1", "n$it", MediaType.MOVIE, "$tag:$it") }
            } else {
                (6..10).map { MediaItem("srv-1", "n$it", MediaType.MOVIE, "$tag:$it") }
            }
            val hasMore = query.page.offset == 0
            return PagedResult(
                items = items,
                totalCount = 10,
                hasMore = hasMore,
                nextOffset = if (hasMore) query.page.limit else null,
            )
        }

        private fun filterTag(f: MediaFilter): String = when {
            f.mediaType != null -> "T:${f.mediaType}"
            f.favorite != null -> "F:${f.favorite}"
            f.played != null -> "P:${f.played}"
            f.year != null -> "Y:${f.year}"
            else -> "NONE"
        }
    }

    private class RegistryWithQuery(private val handle: ProviderHandle) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override fun create(server: MediaServer): ProviderHandle = handle
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun descriptors() = emptyList<ProviderDescriptor>()
    }

    private fun handleOf(lib: FakeQueryLibrary): ProviderHandle =
        ProviderHandle(provider = FakeProvider(), library = lib, query = lib)

    private fun vm(handle: ProviderHandle): LibraryViewModel = LibraryViewModel(
        SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
        FakeServerStore(server), RegistryWithQuery(handle), noOpLogger,
    )

    private val fargo = MediaItem("srv-1", "fargo", MediaType.SERIES, "冰血暴")

    // ---- 筛选变更：新 filter 下发 + offset 重置 ----

    @Test
    fun `onFilterSelected re-requests from offset zero with new filter`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onFilterSelected(MediaFilter(mediaType = MediaType.SERIES))
        advanceUntilIdle()

        assertEquals(
            listOf(MediaFilter(), MediaFilter(mediaType = MediaType.SERIES)),
            lib.requests.map { it.filter },
        )
        assertTrue(lib.requests.all { it.page.offset == 0 })
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size)
        assertTrue(state.items.all { it.title.startsWith("T:SERIES") })
        assertEquals(MediaFilter(mediaType = MediaType.SERIES), state.filter)
    }

    @Test
    fun `same filter does not reload`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onFilterSelected(MediaFilter())
        advanceUntilIdle()

        assertEquals(1, lib.requests.size)
    }

    @Test
    fun `failed filter change rolls back so retry uses previous working filter`() = runTest {
        val rejected = MediaFilter(favorite = true)
        val lib = FakeQueryLibrary(failFilter = rejected)
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onFilterSelected(rejected)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is LibraryUiState.Error)

        viewModel.load()
        advanceUntilIdle()

        assertEquals(listOf(MediaFilter(), rejected, MediaFilter()), lib.requests.map { it.filter })
        val recovered = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(recovered.filter.isDefault)
        assertTrue(recovered.items.all { it.title.startsWith("NONE:") })
    }

    // ---- 容器作用域核心：进子级重置 / 回父级恢复 ----

    @Test
    fun `openFolder resets child filter and goToParent restores parent filter`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        // 父容器选 SERIES
        viewModel.onFilterSelected(MediaFilter(mediaType = MediaType.SERIES))
        advanceUntilIdle()
        // 进 Fargo 子容器
        viewModel.openFolder(fargo)
        advanceUntilIdle()
        // 子容器 query：filter 必须重置为默认（否则 Season 列表会被 SERIES 过滤打空）
        // init(default) → SERIES → 子容器重置(default)
        assertEquals(
            listOf(MediaFilter(), MediaFilter(mediaType = MediaType.SERIES), MediaFilter()),
            lib.requests.map { it.filter },
        )
        val child = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(child.filter.isDefault)

        // 返回父容器：SERIES 筛选恢复（init/Series/child-default/parent-Series 共 4 次请求）
        viewModel.goToParent()
        advanceUntilIdle()
        assertEquals(
            listOf(
                MediaFilter(),
                MediaFilter(mediaType = MediaType.SERIES),
                MediaFilter(),
                MediaFilter(mediaType = MediaType.SERIES),
            ),
            lib.requests.map { it.filter },
        )
        val parent = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(MediaFilter(mediaType = MediaType.SERIES), parent.filter)
        // 恢复后的父容器内容按 SERIES 过滤
        assertTrue(parent.items.all { it.title.startsWith("T:SERIES") })
    }

    @Test
    fun `filter survives multi level navigation round trip`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onFilterSelected(MediaFilter(favorite = true))
        advanceUntilIdle()
        viewModel.openFolder(fargo)
        advanceUntilIdle()
        val sub = MediaItem("srv-1", "sub", MediaType.FOLDER, "子目录")
        viewModel.openFolder(sub)
        advanceUntilIdle()
        viewModel.goToParent()
        advanceUntilIdle()
        viewModel.goToParent()
        advanceUntilIdle()

        // 两层往返后：根层 favorite=true 恢复
        // init → favorite → fargo 层(重置) → sub 层(重置) → 回 fargo(恢复子层默认) → 回根(恢复 favorite)
        assertEquals(
            listOf(
                MediaFilter(),
                MediaFilter(favorite = true),
                MediaFilter(),
                MediaFilter(),
                MediaFilter(),
                MediaFilter(favorite = true),
            ),
            lib.requests.map { it.filter },
        )
    }

    // ---- race：父容器 filter 请求在途，进子容器不得被污染 ----

    @Test
    fun `non-cooperative stale parent response does not pollute child after openFolder`() = runTest {
        val lib = FakeQueryLibrary(stallOffset = 0, stallLibraryId = "view1")
        val viewModel = vm(handleOf(lib))
        // view1 的 load 卡住（携带 SERIES filter）；openFolder 取消后 fake 故意
        // 吞掉 CancellationException 并照样返回旧数据（non-cooperative，评审 P2）——
        // generation guard 必须把这条晚到的 stale response 丢弃
        viewModel.onFilterSelected(MediaFilter(mediaType = MediaType.SERIES))
        runCurrent()

        viewModel.openFolder(fargo)
        advanceUntilIdle()
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size)
        assertTrue(
            "子容器不得被父容器晚到的 stale 响应污染，实际：${state.items.map { it.title }}",
            state.items.all { it.title.startsWith("NONE:") },
        )
        assertTrue(state.filter.isDefault)
        // stale 响应确实发生过：fake 记录了 init + SERIES 两次 view1 请求（第二次被取消后仍返回），
        // 其响应被 generation guard 丢弃——子容器状态只来自自己的 child load
        assertEquals(2, lib.requests.count { it.page.offset == 0 })
    }

    // ---- loadMore 沿用同一 filter 快照 ----

    @Test
    fun `loadMore carries the same filter snapshot`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onFilterSelected(MediaFilter(year = 2024))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertTrue(lib.requests.size >= 2)
        // init load 是无筛选；筛选后所有请求（含 loadMore）都携带同一 filter 快照
        assertTrue(lib.requests.drop(1).all { it.filter == MediaFilter(year = 2024) })
        assertEquals(10, (viewModel.uiState.value as LibraryUiState.Content).items.size)
    }

    // ---- 能力过滤 ----

    @Test
    fun `filter menu filtered by provider capabilities`() = runTest {
        val lib = FakeQueryLibrary(
            capabilities = MediaQueryCapabilities(
                sortFields = MediaSortField.entries.toSet(),
                filterFields = setOf(MediaFilterField.MEDIA_TYPE, MediaFilterField.YEAR),
            ),
        )
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(listOf(MediaFilterField.MEDIA_TYPE, MediaFilterField.YEAR), state.filterFields)
    }

    @Test
    fun `provider without query capability hides filter entry`() = runTest {
        val libraryOnly = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
                PagedResult(
                    items = listOf(MediaItem("srv-1", "m1", MediaType.MOVIE, "M1")),
                    totalCount = 1, hasMore = false,
                )
            override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
            override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()
        }
        val handle = ProviderHandle(provider = FakeProvider(), library = libraryOnly, query = null)
        val viewModel = vm(handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertTrue(state.filterFields.isEmpty())
        assertTrue(state.filter.isDefault)
    }

    // ---- 菜单顺序常量 ----

    @Test
    fun `filter menu order covers all fields in user order`() {
        assertEquals(
            listOf(
                MediaFilterField.MEDIA_TYPE,
                MediaFilterField.YEAR,
                MediaFilterField.PLAYED,
                MediaFilterField.FAVORITE,
            ),
            FILTER_MENU_ORDER,
        )
    }
}
