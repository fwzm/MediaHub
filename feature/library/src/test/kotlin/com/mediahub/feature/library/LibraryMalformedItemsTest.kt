package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
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
 * 个别媒体服返回畸形列表数据（重复 Id / 缺 Id）时的首页-媒体库行为（用户条目 89 / 审计 79）。
 *
 * 真实崩溃路径：`LibraryScreen` 的 `items(libraries, key = { it.id })` 与
 * `gridItems(items, key = { it.id })` 以 id 作为 Compose key；重复或空白 key 会抛
 * `IllegalArgumentException: Key ... was already used` 直接闪退。VM 必须在把
 * Provider 结果交给 UI 前收敛为**唯一且非空**的 id 集合，并对整页畸形给出可见错误态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryMalformedItemsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private class FakeLibrary(
        private val libraries: List<MediaLibrary> = emptyList(),
        private val items: List<MediaItem> = emptyList(),
    ) : MediaLibraryProvider {
        override suspend fun getLibraries(): List<MediaLibrary> = libraries
        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
            PagedResult(items = items, totalCount = items.size)

        override suspend fun getSeasons(seriesId: String): List<com.mediahub.model.Season> = emptyList()
        override suspend fun getEpisodes(seasonId: String): List<com.mediahub.model.Episode> = emptyList()
    }

    private class FakeProvider : MediaProvider {
        override val serverId: String = "srv-1"
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor = ProviderDescriptor(
            id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
            category = com.mediahub.provider.api.ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(com.mediahub.provider.api.ProviderCapability.LIBRARY),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = com.mediahub.provider.api.ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): com.mediahub.provider.api.ConnectionStatus =
            com.mediahub.provider.api.ConnectionStatus(ok = true)
    }

    private class FakeRegistry(private val library: MediaLibraryProvider) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle =
            ProviderHandle(provider = FakeProvider(), library = library)
        override fun descriptors() = emptyList<ProviderDescriptor>()
    }

    private class FakeServerStore(private val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server
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

    private fun vm(library: MediaLibraryProvider, libraryId: String) = LibraryViewModel(
        SavedStateHandle(mapOf("serverId" to "srv-1", "libraryId" to libraryId, "name" to "Emby")),
        FakeServerStore(server()), FakeRegistry(library), noOpLogger,
    )

    // ---- 条目页：重复 Id 必须收敛为唯一 key（否则 LazyVerticalGrid 抛异常闪退） ----

    @Test
    fun `duplicate item ids from a compat server collapse to unique grid keys`() = runTest(dispatcher) {
        val library = FakeLibrary(
            items = listOf(
                MediaItem("srv-1", "dup", MediaType.MOVIE, "电影A"),
                MediaItem("srv-1", "dup", MediaType.MOVIE, "电影A"),
                MediaItem("srv-1", "ok-2", MediaType.MOVIE, "电影B"),
            ),
        )
        val vm = vm(library, "view1")
        advanceUntilIdle()

        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(listOf("dup", "ok-2"), state.items.map { it.id })
        assertEquals("Compose key 必须唯一", state.items.size, state.items.map { it.id }.distinct().size)
    }

    @Test
    fun `items without an id are skipped but valid siblings are still shown`() = runTest(dispatcher) {
        val library = FakeLibrary(
            items = listOf(
                MediaItem("srv-1", "", MediaType.MOVIE, "缺 Id 的条目"),
                MediaItem("srv-1", "ok-1", MediaType.MOVIE, "电影A"),
                MediaItem("srv-1", "   ", MediaType.MOVIE, "空白 Id 的条目"),
            ),
        )
        val vm = vm(library, "view1")
        advanceUntilIdle()

        val state = vm.uiState.value as LibraryUiState.Content
        assertEquals(listOf("ok-1"), state.items.map { it.id })
    }

    @Test
    fun `a page where every item lacks an id surfaces an error state instead of crashing`() = runTest(dispatcher) {
        val library = FakeLibrary(
            items = listOf(
                MediaItem("srv-1", "", MediaType.MOVIE, "缺 Id 的条目"),
                MediaItem("srv-1", "", MediaType.MOVIE, "另一条缺 Id 的条目"),
            ),
        )
        val vm = vm(library, "view1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("整页畸形必须是可见错误态（实际：$state）", state is LibraryUiState.Error)
        assertEquals(ProviderException.ErrorCode.PARSE, (state as LibraryUiState.Error).errorCode)
    }

    // ---- 库列表：同样以 id 作 LazyColumn key ----

    @Test
    fun `duplicate library ids collapse to unique keys`() = runTest(dispatcher) {
        val library = FakeLibrary(
            libraries = listOf(
                MediaLibrary("srv-1", "view1", "电影", LibraryType.MOVIES),
                MediaLibrary("srv-1", "view1", "电影（重复）", LibraryType.MOVIES),
                MediaLibrary("srv-1", "view2", "剧集", LibraryType.TV_SHOWS),
            ),
        )
        val vm = vm(library, "root")
        advanceUntilIdle()

        val state = vm.uiState.value as LibraryUiState.Libraries
        assertEquals(listOf("view1", "view2"), state.libraries.map { it.id })
    }
}
