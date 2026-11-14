package com.mediahub.feature.detail

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.feature.detail.source.CanonicalSourceResolver
import com.mediahub.metadata.DefaultMetadataRegistry
import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataEngine
import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataProvider
import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSource
import com.mediahub.metadata.match.ManualMatchService
import com.mediahub.metadata.match.MetadataMatch
import com.mediahub.metadata.match.MetadataMatchStore
import com.mediahub.metadata.ratings.RatingsService
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 详情页手动匹配入口（条目 51 / 67）：搜索 → 选候选 → 确认写回。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailMetadataTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    private class FakeLogger : Logger {
        override fun d(tag: LogTag, message: String) {}
        override fun i(tag: LogTag, message: String) {}
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {}
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {}
    }

    private class FakeMatchStore : MetadataMatchStore {
        val entries = mutableMapOf<Pair<String, String>, MetadataMatch>()

        override suspend fun get(serverId: String, itemId: String): MetadataMatch? =
            entries[serverId to itemId]

        override suspend fun put(serverId: String, itemId: String, match: MetadataMatch) {
            entries[serverId to itemId] = match
        }
    }

    private class FakeMetadataProvider(
        private val results: List<MetadataResult> = listOf(
            MetadataResult(MetadataSource.TMDB, "movie/550", "搏击俱乐部", 1999),
            MetadataResult(MetadataSource.TMDB, "movie/551", "搏击俱乐部2", 2001),
        ),
        private val detail: MetadataDetail? = MetadataDetail(
            source = MetadataSource.TMDB,
            id = "movie/550",
            title = "搏击俱乐部",
            year = 1999,
            rating = 8.4,
        ),
        private val error: MetadataException? = null,
    ) : MetadataProvider {

        override val source: MetadataSource = MetadataSource.TMDB

        override suspend fun search(query: String, year: Int?): List<MetadataResult> {
            error?.let { throw it }
            return results
        }

        override suspend fun detail(metadataId: String): MetadataDetail? = detail
    }

    private val testServer = MediaServer(
        id = "srv",
        name = "Test",
        type = ServerType.EMBY,
        baseUrl = "https://example.com",
        createdAtEpochMs = 0L,
    )

    private fun fakeProvider(): com.mediahub.provider.api.MediaProvider =
        object : com.mediahub.provider.api.MediaProvider {
            override val serverId = "srv"
            override val type = ServerType.EMBY
            override val displayName = "Test"
            override val descriptor = com.mediahub.provider.api.ProviderDescriptor(
                id = "emby",
                serverType = ServerType.EMBY,
                displayName = "Emby",
                category = com.mediahub.provider.api.ProviderCategory.MEDIA_SERVER,
                declaredCapabilities = setOf(
                    com.mediahub.provider.api.ProviderCapability.DETAIL,
                    com.mediahub.provider.api.ProviderCapability.LIBRARY,
                ),
                authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
                status = com.mediahub.provider.api.ProviderStatus.STABLE,
            )

            override suspend fun testConnection() =
                com.mediahub.provider.api.ConnectionStatus.Unknown
        }

    private fun fakeServerStore(): ServerStore = object : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())
        override suspend fun getServer(id: String): MediaServer? = testServer
    }

    private fun engine(provider: MetadataProvider?): MetadataEngine = MetadataEngine(
        available = provider != null,
        manualMatch = ManualMatchService(listOfNotNull(provider)),
        ratings = RatingsService(emptyList()),
        registry = DefaultMetadataRegistry(listOfNotNull(provider)),
    )

    private fun createViewModel(
        engine: MetadataEngine,
        store: MetadataMatchStore,
        title: String = "搏击俱乐部",
        year: Int? = 1999,
    ): DetailViewModel {
        val item = MediaItem(serverId = "srv", id = "test", type = MediaType.MOVIE, title = title, year = year)
        val detail = MediaDetail(item = item)
        val registry = object : com.mediahub.provider.api.MediaProviderRegistry {
            override fun factoryFor(type: ServerType) = null
            override val supportedTypes = emptySet<ServerType>()
            override fun descriptors() = emptyList<com.mediahub.provider.api.ProviderDescriptor>()
            override fun create(server: MediaServer) = com.mediahub.provider.api.ProviderHandle(
                provider = fakeProvider(),
                detail = object : com.mediahub.provider.api.MediaDetailProvider {
                    override suspend fun getItemDetail(itemId: String) = detail
                },
                library = object : com.mediahub.provider.api.MediaLibraryProvider {
                    override suspend fun getLibraries() = emptyList<MediaLibrary>()
                    override suspend fun getItems(libraryId: String, page: PageRequest) =
                        PagedResult(items = emptyList<MediaItem>())

                    override suspend fun getSeasons(seriesId: String) = error("legacy")
                    override suspend fun getEpisodes(seasonId: String) = error("legacy")
                },
            )
        }
        return DetailViewModel(
            SavedStateHandle(mapOf("serverId" to "srv", "itemId" to "dGVzdA")),
            fakeServerStore(),
            registry,
            CanonicalSourceResolver(fakeServerStore(), registry, FakeLogger()),
            FakeLogger(),
            engine,
            store,
        )
    }

    @Test
    fun `unavailable engine keeps match entry hidden and dialog inert`() = runTest(testDispatcher) {
        val store = FakeMatchStore()
        val vm = createViewModel(engine(null), store)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.metadataState.value.available)
        vm.openMatchDialog()
        assertFalse(vm.matchState.value.visible)
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `search fills candidates with scores and prefilled title`() = runTest(testDispatcher) {
        val vm = createViewModel(engine(FakeMetadataProvider()), FakeMatchStore())
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.metadataState.value.available)
        vm.openMatchDialog()
        assertEquals("搏击俱乐部", vm.matchState.value.query)

        vm.searchMetadataMatch()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.matchState.value
        assertFalse(state.searching)
        assertEquals(2, state.candidates.size)
        assertEquals("movie/550", state.candidates.first().result.id)
        assertTrue(state.candidates.first().score >= 80)
        assertNull(state.error)
    }

    @Test
    fun `confirming a candidate persists and exposes the match`() = runTest(testDispatcher) {
        val store = FakeMatchStore()
        val vm = createViewModel(engine(FakeMetadataProvider()), store)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.openMatchDialog()
        vm.searchMetadataMatch()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.applyMetadataMatch(vm.matchState.value.candidates.first())
        testDispatcher.scheduler.advanceUntilIdle()

        val saved = store.entries["srv" to "test"]
        assertNotNull(saved)
        assertEquals("movie/550", saved?.metadataId)
        assertEquals("movie/550", vm.metadataState.value.match?.metadataId)
        assertFalse(vm.matchState.value.visible)
    }

    @Test
    fun `search failure surfaces a readable error without crashing`() = runTest(testDispatcher) {
        val vm = createViewModel(
            engine(FakeMetadataProvider(error = MetadataException(500, "元数据请求失败（HTTP 500）"))),
            FakeMatchStore(),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        vm.openMatchDialog()
        vm.searchMetadataMatch()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.matchState.value
        assertFalse(state.searching)
        assertTrue(state.candidates.isEmpty())
        assertNotNull(state.error)
        assertTrue(vm.uiState.value is DetailUiState.Content)
    }

    @Test
    fun `stored match is restored on load`() = runTest(testDispatcher) {
        val store = FakeMatchStore()
        store.entries["srv" to "test"] = MetadataMatch(
            source = MetadataSource.TMDB,
            metadataId = "movie/550",
            title = "搏击俱乐部",
            year = 1999,
        )
        val vm = createViewModel(engine(FakeMetadataProvider()), store)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("movie/550", vm.metadataState.value.match?.metadataId)
    }
}
