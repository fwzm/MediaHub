package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.Episode
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaListQuery
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaSort
import com.mediahub.model.MediaQueryCapabilities
import com.mediahub.model.MediaSortField
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
import com.mediahub.model.SortDirection
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Library 排序（Phase 1C-2）ViewModel 测试：
 * 服务端排序下发 / 改排序取消在途并重置分页 / 无 Query 能力回退隐藏入口 / 能力过滤菜单。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySortViewModelTest {

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

    /** 承载 LIBRARY + QUERY 两个能力的 fake；内容按 sort 打标以便区分新旧响应。 */
    private class FakeQueryLibrary(
        override val capabilities: MediaQueryCapabilities =
            MediaQueryCapabilities(sortFields = MediaSortField.entries.toSet()),
        private val stallOffset: Int? = null,
    ) : MediaLibraryProvider, MediaQueryLibraryProvider {
        val sortRequests = mutableListOf<MediaSort>()
        val offsets = mutableListOf<Int>()

        override suspend fun getLibraries(): List<MediaLibrary> = emptyList()
        override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
        override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()

        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
            getItems(libraryId, MediaListQuery(page = page))

        override suspend fun getItems(libraryId: String, query: MediaListQuery): PagedResult<MediaItem> {
            sortRequests += query.sort
            offsets += query.page.offset
            if (query.page.offset == stallOffset) delay(200)
            val items = if (query.page.offset == 0) {
                (1..5).map { MediaItem("srv-1", "n$it", MediaType.MOVIE, "S:${query.sort.field}:$it") }
            } else {
                (6..10).map { MediaItem("srv-1", "n$it", MediaType.MOVIE, "S:${query.sort.field}:$it") }
            }
            val hasMore = query.page.offset == 0
            return PagedResult(
                items = items,
                totalCount = 10,
                hasMore = hasMore,
                nextOffset = if (hasMore) 5 else null,
            )
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

    // ---- 改排序：新 sort 下发服务器 + 分页重置 ----

    @Test
    fun `onSortSelected re-requests from offset zero with new sort`() = runTest {
        val lib = FakeQueryLibrary()
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        viewModel.onSortSelected(MediaSort(MediaSortField.DATE_ADDED, SortDirection.DESC))
        advanceUntilIdle()

        assertEquals(
            listOf(MediaSortField.SERVER_DEFAULT, MediaSortField.DATE_ADDED),
            lib.sortRequests.map { it.field },
        )
        assertEquals(SortDirection.DESC, lib.sortRequests.last().direction)
        assertEquals(listOf(0, 0), lib.offsets) // 第二次从 offset=0 重新拉
        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size) // 重置回第一页
        assertTrue(state.items.all { it.title.startsWith("S:DATE_ADDED") })
        assertEquals(MediaSort(MediaSortField.DATE_ADDED, SortDirection.DESC), state.sort)
    }

    @Test
    fun `sort change cancels in-flight loadMore without stale append`() = runTest {
        val lib = FakeQueryLibrary(stallOffset = 5)
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()
        assertEquals(5, (viewModel.uiState.value as LibraryUiState.Content).items.size)

        // loadMore 在途（卡在 offset=5 的 delay）
        viewModel.loadMore()
        runCurrent()
        // 改排序：取消在途 loadMore → 重置 → 新 sort 第一页
        viewModel.onSortSelected(MediaSort(MediaSortField.COMMUNITY_RATING, SortDirection.DESC))
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size)
        assertTrue(state.items.all { it.title.startsWith("S:COMMUNITY_RATING") })
        // loadMore 被取消前已合法发出请求（记录先于 stall）；取消只阻止 stale 结果落地
        assertEquals(
            listOf(
                MediaSortField.SERVER_DEFAULT, // 首屏
                MediaSortField.SERVER_DEFAULT, // 被取消的 loadMore（offset=5）
                MediaSortField.COMMUNITY_RATING, // 改排序后的重置加载
            ),
            lib.sortRequests.map { it.field },
        )
        assertEquals(listOf(0, 5, 0), lib.offsets)
    }

    // ---- 能力过滤：菜单只含 Provider 声明的字段 ----

    @Test
    fun `sort menu filtered by provider capabilities in menu order`() = runTest {
        val caps = MediaQueryCapabilities(
            sortFields = setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE, MediaSortField.COMMUNITY_RATING),
        )
        val lib = FakeQueryLibrary(capabilities = caps)
        val viewModel = vm(handleOf(lib))
        advanceUntilIdle()

        val state = viewModel.uiState.value as LibraryUiState.Content
        assertEquals(
            listOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE, MediaSortField.COMMUNITY_RATING),
            state.sortFields,
        )
    }

    // ---- 无 Query 能力：回退旧接口、隐藏排序入口 ----

    @Test
    fun `provider without query capability falls back and hides sort entry`() = runTest {
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
        assertTrue(state.sortFields.isEmpty()) // 入口隐藏
        assertEquals(1, state.items.size)
        assertEquals(MediaSortField.SERVER_DEFAULT, state.sort.field)
    }

    // ---- 菜单顺序常量：完整且以默认开头 ----

    @Test
    fun `sort menu order covers all fields starting with server default`() {
        assertEquals(MediaSortField.entries.size, SORT_MENU_ORDER.size)
        assertEquals(MediaSortField.SERVER_DEFAULT, SORT_MENU_ORDER.first())
        assertEquals(SORT_MENU_ORDER.size, SORT_MENU_ORDER.distinct().size)
    }

    // ---- C2：用户排序激活后 Provider 顺序权威，SERVER_DEFAULT 保留目录优先 ----

    @Test
    fun `provider order preserved for user-selected sorts only`() {
        assertFalse(shouldPreserveProviderOrder(MediaSort(MediaSortField.SERVER_DEFAULT)))
        assertTrue(shouldPreserveProviderOrder(MediaSort(MediaSortField.TITLE)))
        assertTrue(shouldPreserveProviderOrder(MediaSort(MediaSortField.DATE_ADDED, SortDirection.DESC)))
        assertTrue(shouldPreserveProviderOrder(MediaSort(MediaSortField.RANDOM)))
    }
}
