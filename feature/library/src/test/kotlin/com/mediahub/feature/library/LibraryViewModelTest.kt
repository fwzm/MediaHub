package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.Episode
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.model.Season
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LibraryViewModel 浏览路由测试（Phase 1B-1，评审 #12 的 12-15 项）。
 * 覆盖：root→getLibraries、libraryId≠root→getItems、open container→item.id 作 parentId、goToParent 恢复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private class FakeLibrary(
        var requestedLibraries: Int = 0,
        var requestedItemsParentIds: MutableList<String> = mutableListOf(),
    ) : MediaLibraryProvider {
        override suspend fun getLibraries(): List<MediaLibrary> {
            requestedLibraries++
            return listOf(
                MediaLibrary("srv-1", "view1", "电影", LibraryType.MOVIES),
                MediaLibrary("srv-1", "view2", "剧集", LibraryType.TV_SHOWS),
            )
        }

        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
            requestedItemsParentIds += libraryId
            return PagedResult(
                items = listOf(
                    MediaItem("srv-1", "series-1", MediaType.SERIES, "剧1"),
                    MediaItem("srv-1", "season-1", MediaType.SEASON, "第1季"),
                ),
                totalCount = 2,
            )
        }

        override suspend fun getSeasons(seriesId: String): List<Season> = emptyList()
        override suspend fun getEpisodes(seasonId: String): List<Episode> = emptyList()
    }

    private class FakeProvider : MediaProvider {
        override val serverId: String = "srv-1"
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.LIBRARY),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): com.mediahub.provider.api.ConnectionStatus =
            com.mediahub.provider.api.ConnectionStatus(ok = true)
    }

    private class FakeRegistry(val library: MediaLibraryProvider) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle? =
            ProviderHandle(provider = FakeProvider(), library = library)
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class FakeServerStore(val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = if (id == server.id) server else null
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun server() = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY, baseUrl = "http://h", createdAtEpochMs = 0,
    )

    // ---- 12：root → getLibraries ----

    @Test
    fun `root loads libraries not items`() = runTest {
        val library = FakeLibrary()
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "root", "name" to "Emby")),
            FakeServerStore(server()), FakeRegistry(library), noOpLogger,
        )
        advanceUntilIdle()

        assertEquals(1, library.requestedLibraries)
        assertTrue(library.requestedItemsParentIds.isEmpty())
        assertTrue(vm.uiState.value is LibraryUiState.Libraries)
    }

    // ---- 13：libraryId != root → getItems(parentId = libraryId) ----

    @Test
    fun `non-root loads items with libraryId as parentId`() = runTest {
        val library = FakeLibrary()
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(library), noOpLogger,
        )
        advanceUntilIdle()

        assertEquals(0, library.requestedLibraries)
        assertEquals(listOf("view1"), library.requestedItemsParentIds)
        assertTrue(vm.uiState.value is LibraryUiState.Content)
    }

    // ---- 14：open Series/Season → 用 item.id 作下一层 parentId ----

    @Test
    fun `open container uses item id as next parentId`() = runTest {
        val library = FakeLibrary()
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(library), noOpLogger,
        )
        advanceUntilIdle()
        library.requestedItemsParentIds.clear()

        // 打开 Series（series-1）
        vm.openFolder(MediaItem("srv-1", "series-1", MediaType.SERIES, "剧1"))
        advanceUntilIdle()

        assertEquals(listOf("series-1"), library.requestedItemsParentIds)
    }

    // ---- 15：goToParent 恢复上一层 parentId ----

    @Test
    fun `goToParent restores previous parentId`() = runTest {
        val library = FakeLibrary()
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(library), noOpLogger,
        )
        advanceUntilIdle() // 初次 getItems(view1)

        // 进入 series-1
        vm.openFolder(MediaItem("srv-1", "series-1", MediaType.SERIES, "剧1"))
        advanceUntilIdle()
        assertEquals(listOf("view1", "series-1"), library.requestedItemsParentIds)

        // 返回上级 → 重新 getItems(view1)
        vm.goToParent()
        advanceUntilIdle()
        assertEquals(listOf("view1", "series-1", "view1"), library.requestedItemsParentIds)
    }

    // ---- Pagination (1B-3.2) ----

    private class PaginatedLibrary(
        private val pages: List<List<MediaItem>>,
    ) : MediaLibraryProvider {
        var callCount = 0
        override suspend fun getLibraries(): List<MediaLibrary> = emptyList()
        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
            // 按 offset 决定返回哪一页（而非 callCount），支持不同 parentId 各自分页
            val idx = (page.offset / 200).coerceIn(0, pages.size - 1)
            callCount++
            val items = if (idx < pages.size) pages[idx] else emptyList()
            return PagedResult(
                items = items,
                totalCount = pages.sumOf { it.size },
                hasMore = idx < pages.size - 1,
                nextOffset = if (idx < pages.size - 1) (idx + 1) * 200 else null,
            )
        }
        override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
        override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()
    }

    private fun createPaginatedVm(pages: List<List<MediaItem>>): LibraryViewModel {
        val library = PaginatedLibrary(pages)
        return LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(library), noOpLogger,
        )
    }

    @Test
    fun firstPageHasMoreTrue() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size)
        assertTrue(state.hasMore)
        assertTrue(!state.isLoadingMore)
    }

    @Test
    fun loadMoreAppendsSecondPage() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(10, state.items.size)
        assertTrue(!state.hasMore)
    }

    @Test
    fun loadMoreDeduplicates() = runTest {
        // Second page returns same item as first page → should be deduped
        val vm = createPaginatedVm(listOf(
            listOf(MediaItem("srv-1", "dup", MediaType.MOVIE, "Movie A")),
            listOf(MediaItem("srv-1", "dup", MediaType.MOVIE, "Movie A")),
        ))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(1, state.items.size)
    }

    @Test
    fun loadMoreNotConcurrent() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        vm.loadMore()
        vm.loadMore() // second call should be ignored
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(10, state.items.size) // only one loadMore executed
    }

    @Test
    fun loadMoreErrorKeepsFirstPage() = runTest {
        val lib = object : MediaLibraryProvider {
            var callCount = 0
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                callCount++
                return if (callCount == 1) PagedResult(
                    items = (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
                    totalCount = 10, hasMore = true, nextOffset = 5,
                ) else throw RuntimeException("load more failed")
            }
            override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
            override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()
        }
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(lib), noOpLogger,
        )
        advanceUntilIdle()
        assertEquals(5, (vm.uiState.value as LibraryUiState.Content).items.size)
        vm.loadMore()
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(5, state.items.size) // first page preserved
        assertTrue(state.loadMoreError != null)
    }

    @Test
    fun retryLoadMoreAfterError() = runTest {
        var failNext = true
        val lib = object : MediaLibraryProvider {
            var callCount = 0
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                callCount++
                return when {
                    callCount == 1 -> PagedResult(
                        items = (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
                        totalCount = 10, hasMore = true, nextOffset = 5,
                    )
                    failNext -> { failNext = false; throw RuntimeException("fail") }
                    else -> PagedResult(
                        items = (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
                        totalCount = 10, hasMore = false, nextOffset = null,
                    )
                }
            }
            override suspend fun getSeasons(seriesId: String) = emptyList<Season>()
            override suspend fun getEpisodes(seasonId: String) = emptyList<Episode>()
        }
        val vm = LibraryViewModel(
            SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to "view1", "name" to "电影")),
            FakeServerStore(server()), FakeRegistry(lib), noOpLogger,
        )
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertTrue((vm.uiState.value as LibraryUiState.Content).loadMoreError != null)
        vm.loadMore() // retry
        advanceUntilIdle()
        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(10, state.items.size)
        assertTrue(state.loadMoreError == null)
    }

    @Test
    fun openFolderResetsPagination() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(10, (vm.uiState.value as LibraryUiState.Content).items.size)
        // Open folder → should reset to first page
        vm.openFolder(MediaItem("srv-1", "sub", MediaType.FOLDER, "Sub"))
        advanceUntilIdle()
        assertEquals(5, (vm.uiState.value as LibraryUiState.Content).items.size)
    }

    @Test
    fun goToParentResetsPagination() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(10, (vm.uiState.value as LibraryUiState.Content).items.size)
        // Enter folder, then go back
        vm.openFolder(MediaItem("srv-1", "sub", MediaType.FOLDER, "Sub"))
        advanceUntilIdle()
        vm.goToParent()
        advanceUntilIdle()
        assertEquals(5, (vm.uiState.value as LibraryUiState.Content).items.size)
    }

    @Test
    fun refreshResetsToFirstPage() = runTest {
        val vm = createPaginatedVm(listOf(
            (1..5).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
            (6..10).map { MediaItem("srv-1", "item$it", MediaType.MOVIE, "Movie $it") },
        ))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(10, (vm.uiState.value as LibraryUiState.Content).items.size)
        vm.load() // refresh
        advanceUntilIdle()
        assertEquals(5, (vm.uiState.value as LibraryUiState.Content).items.size)
    }
}
