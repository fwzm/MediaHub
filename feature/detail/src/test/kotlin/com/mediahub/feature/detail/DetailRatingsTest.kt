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
import com.mediahub.metadata.ratings.PlatformRating
import com.mediahub.metadata.ratings.RatingPlatform
import com.mediahub.metadata.ratings.RatingQuery
import com.mediahub.metadata.ratings.RatingsService
import com.mediahub.metadata.ratings.RatingsSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 详情页多平台评分（条目 115）：并排展示，无数据平台不占位。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailRatingsTest {

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

    private class FakeMatchStore(initial: Map<Pair<String, String>, MetadataMatch> = emptyMap()) :
        MetadataMatchStore {
        val entries = initial.toMutableMap()

        override suspend fun get(serverId: String, itemId: String): MetadataMatch? =
            entries[serverId to itemId]

        override suspend fun put(serverId: String, itemId: String, match: MetadataMatch) {
            entries[serverId to itemId] = match
        }
    }

    private class FakeRatingsSource(
        private val ratings: List<PlatformRating> = emptyList(),
        private val error: MetadataException? = null,
    ) : RatingsSource {
        override val name: String = "fake"

        override suspend fun ratings(query: RatingQuery): List<PlatformRating> {
            error?.let { throw it }
            return ratings
        }
    }

    private class FakeMetadataProvider(
        private val detail: MetadataDetail = MetadataDetail(
            source = MetadataSource.TMDB,
            id = "movie/550",
            title = "搏击俱乐部",
            year = 1999,
            rating = 8.4,
        ),
    ) : MetadataProvider {

        override val source: MetadataSource = MetadataSource.TMDB

        override suspend fun search(query: String, year: Int?): List<MetadataResult> = emptyList()

        override suspend fun detail(metadataId: String): MetadataDetail = detail
    }

    private val testServer = MediaServer(
        id = "srv",
        name = "Test",
        type = ServerType.EMBY,
        baseUrl = "https://example.com",
        createdAtEpochMs = 0L,
    )

    private fun fakeServerStore(): ServerStore = object : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())
        override suspend fun getServer(id: String): MediaServer? = testServer
    }

    private fun engine(ratingsSource: RatingsSource?): MetadataEngine {
        val provider = FakeMetadataProvider()
        return MetadataEngine(
            available = true,
            manualMatch = ManualMatchService(listOf(provider)),
            ratings = RatingsService(listOfNotNull(ratingsSource)),
            registry = DefaultMetadataRegistry(listOf(provider)),
        )
    }

    private fun createViewModel(engine: MetadataEngine, store: MetadataMatchStore): DetailViewModel {
        val item = MediaItem(serverId = "srv", id = "test", type = MediaType.MOVIE, title = "搏击俱乐部", year = 1999)
        val detail = MediaDetail(item = item)
        val registry = object : com.mediahub.provider.api.MediaProviderRegistry {
            override fun factoryFor(type: ServerType) = null
            override val supportedTypes = emptySet<ServerType>()
            override fun descriptors() = emptyList<com.mediahub.provider.api.ProviderDescriptor>()
            override fun create(server: MediaServer) = com.mediahub.provider.api.ProviderHandle(
                provider = object : com.mediahub.provider.api.MediaProvider {
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
                },
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

    private fun storedMatch(): MetadataMatch = MetadataMatch(
        source = MetadataSource.TMDB,
        metadataId = "movie/550",
        title = "搏击俱乐部",
        year = 1999,
    )

    @Test
    fun `ratings render only platforms that returned data`() = runTest(testDispatcher) {
        val source = FakeRatingsSource(
            listOf(
                PlatformRating(RatingPlatform.IMDB, 8.8, 10),
                PlatformRating(RatingPlatform.ROTTEN_TOMATOES, 92.0, 100),
            ),
        )
        val vm = createViewModel(
            engine(source),
            FakeMatchStore(mapOf(("srv" to "test") to storedMatch())),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val ratings = vm.metadataState.value.ratings
        assertEquals(
            listOf(RatingPlatform.TMDB, RatingPlatform.IMDB, RatingPlatform.ROTTEN_TOMATOES),
            ratings.map { it.platform },
        )
        assertNotNull(vm.metadataState.value.averageRating)
    }

    @Test
    fun `platforms without data are absent instead of placeholders`() = runTest(testDispatcher) {
        val vm = createViewModel(
            engine(FakeRatingsSource(listOf(PlatformRating(RatingPlatform.IMDB, 8.8, 10)))),
            FakeMatchStore(mapOf(("srv" to "test") to storedMatch())),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val platforms = vm.metadataState.value.ratings.map { it.platform }
        assertEquals(listOf(RatingPlatform.TMDB, RatingPlatform.IMDB), platforms)
        assertTrue(platforms.none { it == RatingPlatform.METACRITIC })
    }

    @Test
    fun `failing ratings source degrades without touching detail content`() = runTest(testDispatcher) {
        val vm = createViewModel(
            engine(FakeRatingsSource(error = MetadataException(429, "元数据请求失败（HTTP 429）"))),
            FakeMatchStore(mapOf(("srv" to "test") to storedMatch())),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is DetailUiState.Content)
        assertEquals(
            listOf(RatingPlatform.TMDB),
            vm.metadataState.value.ratings.map { it.platform },
        )
    }

    @Test
    fun `no stored match yields no ratings`() = runTest(testDispatcher) {
        val vm = createViewModel(engine(FakeRatingsSource()), FakeMatchStore())
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.metadataState.value.ratings.isEmpty())
        assertNull(vm.metadataState.value.match)
        assertTrue(vm.uiState.value is DetailUiState.Content)
    }
}
