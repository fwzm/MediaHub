package com.mediahub.feature.library.iptv

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.Episode
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IptvChannelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads all channels first and page state`() = runTest {
        val vm = viewModel(FakeLibrary(channels = defaultChannels()))

        advanceUntilIdle()

        val state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(listOf("全部频道", "央视", "卫视"), state.groups.map { it.name })
        assertEquals("__all__", state.selectedGroupId)
        assertEquals(4, state.items.size)
        assertFalse(state.hasMore)
    }

    @Test
    fun `selecting group reloads that group and clears query`() = runTest {
        val vm = viewModel(FakeLibrary(channels = defaultChannels()))
        advanceUntilIdle()

        vm.selectGroup("央视")
        advanceUntilIdle()

        val state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals("央视", state.selectedGroupId)
        assertEquals("", state.query)
        assertEquals(listOf("CCTV-1", "CCTV-2"), state.items.map { it.title })
    }

    @Test
    fun `search is debounced and calls provider`() = runTest {
        val library = FakeLibrary(channels = defaultChannels())
        val vm = viewModel(library)
        advanceUntilIdle()

        vm.setQuery("湖南")
        advanceTimeBy(299)
        assertEquals(0, library.searchCalls.size)

        advanceTimeBy(2)
        advanceUntilIdle()

        assertEquals(listOf("湖南"), library.searchCalls)
        val state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(listOf("湖南卫视"), state.items.map { it.title })
    }

    @Test
    fun `toggle favorite updates local favorites and filter`() = runTest {
        val favorites = FakeFavorites()
        val vm = viewModel(FakeLibrary(channels = defaultChannels()), favorites)
        advanceUntilIdle()

        val cctv1 = (vm.uiState.value as IptvChannelsUiState.Content).items.first { it.title == "CCTV-1" }
        vm.toggleFavorite(cctv1)
        advanceUntilIdle()

        var state = vm.uiState.value as IptvChannelsUiState.Content
        assertTrue(cctv1.id in state.favorites)

        vm.toggleFavoritesOnly()
        state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(listOf("CCTV-1"), state.items.map { it.title })

        vm.toggleFavorite(cctv1)
        advanceUntilIdle()
        state = vm.uiState.value as IptvChannelsUiState.Content
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `loadMore appends next page`() = runTest {
        val channels = (1..150).map { index ->
            MediaItem(
                serverId = SERVER_ID,
                id = "ch-$index",
                type = MediaType.LIVE_TV,
                title = "频道 $index",
                libraryId = "央视",
            )
        }
        val vm = viewModel(FakeLibrary(channels = channels))
        advanceUntilIdle()

        var state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(100, state.items.size)
        assertTrue(state.hasMore)

        vm.loadMore()
        advanceUntilIdle()

        state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(150, state.items.size)
        assertFalse(state.hasMore)
    }

    @Test
    fun `enabling favorites only loads remaining pages to find favorites`() = runTest {
        val channels = (1..150).map { index ->
            MediaItem(
                serverId = SERVER_ID,
                id = "ch-$index",
                type = MediaType.LIVE_TV,
                title = "频道 $index",
                libraryId = "央视",
            )
        }
        val vm = viewModel(FakeLibrary(channels = channels), FakeFavorites(setOf("ch-120")))
        advanceUntilIdle()

        assertEquals(100, (vm.uiState.value as IptvChannelsUiState.Content).items.size)

        vm.toggleFavoritesOnly()
        advanceUntilIdle()

        val state = vm.uiState.value as IptvChannelsUiState.Content
        assertEquals(listOf("频道 120"), state.items.map { it.title })
        assertFalse(state.hasMore)
    }

    @Test
    fun `error state retries into content`() = runTest {
        val library = FakeLibrary(channels = defaultChannels()).apply { failLibraries = true }
        val vm = viewModel(library)
        advanceUntilIdle()

        assertTrue(vm.uiState.value is IptvChannelsUiState.Error)

        library.failLibraries = false
        vm.retry()
        advanceUntilIdle()

        assertTrue(vm.uiState.value is IptvChannelsUiState.Content)
    }

    private fun viewModel(
        library: FakeLibrary,
        favorites: FakeFavorites = FakeFavorites(),
    ): IptvChannelsViewModel = IptvChannelsViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf("serverId" to SERVER_ID, "name" to "IPTV"),
        ),
        serverStore = FakeServerStore(server()),
        registry = FakeRegistry(library),
        favoritesRepository = favorites,
    )

    private fun server() = MediaServer(
        id = SERVER_ID,
        name = "IPTV",
        type = ServerType.IPTV,
        baseUrl = "http://127.0.0.1/playlist.m3u",
        createdAtEpochMs = 0,
    )

    private fun defaultChannels(): List<MediaItem> = listOf(
        MediaItem(SERVER_ID, "cctv1", MediaType.LIVE_TV, "CCTV-1", libraryId = "央视"),
        MediaItem(SERVER_ID, "cctv2", MediaType.LIVE_TV, "CCTV-2", libraryId = "央视"),
        MediaItem(SERVER_ID, "hunan", MediaType.LIVE_TV, "湖南卫视", libraryId = "卫视"),
        MediaItem(SERVER_ID, "local", MediaType.LIVE_TV, "本地频道", libraryId = "卫视"),
    )

    private class FakeLibrary(
        private val channels: List<MediaItem>,
        var failLibraries: Boolean = false,
    ) : MediaLibraryProvider, MediaSearchProvider {

        val searchCalls = mutableListOf<String>()

        override suspend fun getLibraries(): List<MediaLibrary> {
            if (failLibraries) throw IllegalStateException("播放列表拉取失败")
            val cctv = channels.count { it.libraryId == "央视" }
            val satellite = channels.count { it.libraryId == "卫视" }
            return listOf(
                MediaLibrary(SERVER_ID, "__all__", "全部频道", LibraryType.LIVE_TV, channels.size),
                MediaLibrary(SERVER_ID, "央视", "央视", LibraryType.LIVE_TV, cctv),
                MediaLibrary(SERVER_ID, "卫视", "卫视", LibraryType.LIVE_TV, satellite),
            )
        }

        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
            if (failLibraries) throw IllegalStateException("播放列表拉取失败")
            val scoped = if (libraryId == "__all__") {
                channels
            } else {
                channels.filter { it.libraryId == libraryId }
            }
            return paginate(scoped, page)
        }

        override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> {
            searchCalls += query
            return paginate(channels.filter { it.title.contains(query) }, page)
        }

        override suspend fun getSeasons(seriesId: String): List<Season> = emptyList()
        override suspend fun getEpisodes(seasonId: String): List<Episode> = emptyList()

        private fun paginate(items: List<MediaItem>, page: PageRequest): PagedResult<MediaItem> {
            val start = page.offset.coerceAtLeast(0)
            val paged = items.drop(start).take(page.limit)
            val next = start + paged.size
            val hasMore = next < items.size
            return PagedResult(
                items = paged,
                totalCount = items.size,
                hasMore = hasMore,
                nextOffset = if (hasMore) next else null,
            )
        }
    }

    private class FakeFavorites(initial: Set<String> = emptySet()) : IptvFavoritesRepository {
        private val state = MutableStateFlow(initial)
        override fun favorites(serverId: String): Flow<Set<String>> = state
        override suspend fun toggle(serverId: String, channelId: String): Boolean {
            val current = state.value.toMutableSet()
            val added = current.add(channelId)
            if (!added) current.remove(channelId)
            state.value = current
            return added
        }
    }

    private class FakeRegistry(private val library: FakeLibrary) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override val supportedTypes: Set<ServerType> = setOf(ServerType.IPTV)
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = FakeProvider,
            library = library,
            search = library,
        )
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private object FakeProvider : MediaProvider {
        override val serverId: String = SERVER_ID
        override val type: ServerType = ServerType.IPTV
        override val displayName: String = "IPTV"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "iptv",
            serverType = ServerType.IPTV,
            displayName = "IPTV",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.LIBRARY, ProviderCapability.SEARCH),
            authMethod = com.mediahub.provider.api.AuthMethod.NONE,
            status = ProviderStatus.EXPERIMENTAL,
        )
        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeServerStore(private val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
    }

    private companion object {
        const val SERVER_ID = "srv-iptv"
    }
}
