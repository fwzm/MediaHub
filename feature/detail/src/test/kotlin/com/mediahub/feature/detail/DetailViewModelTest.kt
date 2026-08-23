package com.mediahub.feature.detail

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.model.UserData
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    private class FakeLogger : Logger {
        override fun d(tag: LogTag, message: String) {}
        override fun i(tag: LogTag, message: String) {}
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {}
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {}
    }

    private val testServer = MediaServer(
        id = "srv", name = "Test", type = ServerType.EMBY,
        baseUrl = "https://example.com", createdAtEpochMs = 0L,
    )

    private val stubDescriptor = ProviderDescriptor(
        id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
        category = com.mediahub.provider.api.ProviderCategory.MEDIA_SERVER,
        declaredCapabilities = emptySet(),
        authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
        status = com.mediahub.provider.api.ProviderStatus.STABLE,
    )

    private fun fakeProvider(): MediaProvider = object : MediaProvider {
        override val serverId = "srv"
        override val type = ServerType.EMBY
        override val displayName = "Test"
        override val descriptor = stubDescriptor
        override suspend fun testConnection() = ConnectionStatus.Unknown
    }

    private fun fakeServerStore(): ServerStore = object : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())
        override suspend fun getServer(id: String): MediaServer? = testServer
    }

    private fun makeItem(
        id: String, type: MediaType, seasonNumber: Int? = null,
        episodeNumber: Int? = null, runtimeMs: Long? = null,
        playedPercentage: Double? = null, playCount: Int = 0,
    ): MediaItem = MediaItem(
        serverId = "srv", id = id, type = type, title = id,
        seasonNumber = seasonNumber, episodeNumber = episodeNumber,
        runtimeMs = runtimeMs,
        userData = UserData(playedPercentage = playedPercentage, playCount = playCount),
    )

    private fun createViewModel(
        detail: MediaDetail,
        libraryItems: Map<String, List<MediaItem>> = emptyMap(),
        libraryProvider: MediaLibraryProvider? = null,
        libraryNull: Boolean = false,
    ): DetailViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("serverId" to "srv", "itemId" to "dGVzdA"))
        val lib = when {
            libraryNull -> null
            libraryProvider != null -> libraryProvider
            else -> object : MediaLibraryProvider {
                override suspend fun getLibraries() = emptyList<MediaLibrary>()
                override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                    val items = libraryItems[libraryId] ?: emptyList()
                    return PagedResult(items = items, totalCount = items.size, hasMore = false, nextOffset = null)
                }
                override suspend fun getSeasons(seriesId: String) = error("legacy")
                override suspend fun getEpisodes(seasonId: String) = error("legacy")
            }
        }
        val detailProvider = object : MediaDetailProvider {
            override suspend fun getItemDetail(itemId: String) = detail
        }
        val registry = object : MediaProviderRegistry {
            override fun factoryFor(type: ServerType) = null
            override val supportedTypes = emptySet<ServerType>()
            override fun descriptors() = emptyList<ProviderDescriptor>()
            override fun create(server: MediaServer): ProviderHandle {
                return ProviderHandle(provider = fakeProvider(), detail = detailProvider, library = lib)
            }
        }
        return DetailViewModel(savedStateHandle, fakeServerStore(), registry, FakeLogger())
    }

    @Test
    fun movieDetailDoesNotCallLibrary() = runTest(testDispatcher) {
        val movieItem = makeItem("m1", MediaType.MOVIE)
        val detail = MediaDetail(item = movieItem)
        val vm = createViewModel(detail)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is DetailUiState.Content)
        assertEquals(SeriesBrowseState(), vm.seriesState.value)
    }

    @Test
    fun seriesDetailLoadsSeasons() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-1", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("s2", MediaType.SEASON, seasonNumber = 2))
        val vm = createViewModel(detail, libraryItems = mapOf("series-1" to seasons))
        testDispatcher.scheduler.advanceUntilIdle()
        val browse = vm.seriesState.value
        assertEquals(2, browse.seasons.size)
        assertEquals("s1", browse.selectedSeasonId)
        assertFalse(browse.seasonsLoading)
    }

    @Test
    fun mixedItemsOnlyKeepSeason() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-2", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val mixed = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("mov", MediaType.MOVIE), makeItem("fold", MediaType.FOLDER))
        val vm = createViewModel(detail, libraryItems = mapOf("series-2" to mixed))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.seriesState.value.seasons.size)
        assertEquals("s1", vm.seriesState.value.seasons[0].id)
    }

    @Test
    fun seasonSortingNormalsFirstSpecialsLast() {
        val seasons = listOf(makeItem("s0", MediaType.SEASON, seasonNumber = 0), makeItem("s3", MediaType.SEASON, seasonNumber = 3), makeItem("snull", MediaType.SEASON, seasonNumber = null), makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("s2", MediaType.SEASON, seasonNumber = 2))
        val sorted = DetailViewModel.sortSeasons(seasons)
        assertEquals(listOf("s1", "s2", "s3", "s0", "snull"), sorted.map { it.id })
    }

    @Test
    fun defaultSelectsFirstNormalSeason() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-3", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s0", MediaType.SEASON, seasonNumber = 0), makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        val vm = createViewModel(detail, libraryItems = mapOf("series-3" to seasons))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("s1", vm.seriesState.value.selectedSeasonId)
    }

    @Test
    fun onlySpecialsSelectsFirstSpecial() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-4", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s0", MediaType.SEASON, seasonNumber = 0))
        val vm = createViewModel(detail, libraryItems = mapOf("series-4" to seasons))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("s0", vm.seriesState.value.selectedSeasonId)
    }

    @Test
    fun noSeasonsEmptyState() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-5", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val vm = createViewModel(detail, libraryItems = mapOf("series-5" to emptyList()))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.seriesState.value.seasons.isEmpty())
        assertNull(vm.seriesState.value.selectedSeasonId)
    }

    @Test
    fun selectSeasonLoadsEpisodes() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-6", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        val episodes = listOf(makeItem("e1", MediaType.EPISODE, seasonNumber = 1, episodeNumber = 1, runtimeMs = 3600000L, playedPercentage = 72.0), makeItem("e2", MediaType.EPISODE, seasonNumber = 1, episodeNumber = 2, runtimeMs = 3000000L))
        val vm = createViewModel(detail, libraryItems = mapOf("series-6" to seasons, "s1" to episodes))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.seriesState.value.episodes.size)
        assertEquals(3600000L, vm.seriesState.value.episodes[0].runtimeMs)
        assertEquals(72.0, vm.seriesState.value.episodes[0].userData?.playedPercentage)
        assertFalse(vm.seriesState.value.episodesLoading)
    }

    @Test
    fun mixedEpisodesOnlyKeepEpisode() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-7", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        val mixed = listOf(makeItem("e1", MediaType.EPISODE, episodeNumber = 1), makeItem("mov", MediaType.MOVIE))
        val vm = createViewModel(detail, libraryItems = mapOf("series-7" to seasons, "s1" to mixed))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.seriesState.value.episodes.size)
        assertEquals("e1", vm.seriesState.value.episodes[0].id)
    }

    @Test
    fun seasonSwitchDoesNotReloadDetail() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-8", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("s2", MediaType.SEASON, seasonNumber = 2))
        val episodes1 = listOf(makeItem("e1", MediaType.EPISODE, episodeNumber = 1))
        val episodes2 = listOf(makeItem("e2", MediaType.EPISODE, episodeNumber = 2))
        val vm = createViewModel(detail, libraryItems = mapOf("series-8" to seasons, "s1" to episodes1, "s2" to episodes2))
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectSeason("s2")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is DetailUiState.Content)
        assertEquals("s2", vm.seriesState.value.selectedSeasonId)
        assertEquals(1, vm.seriesState.value.episodes.size)
        assertEquals("e2", vm.seriesState.value.episodes[0].id)
    }

    @Test
    fun raceLateS1ResponseDoesNotOverwriteS2() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-race", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("s2", MediaType.SEASON, seasonNumber = 2))
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                val items = when (libraryId) {
                    "series-race" -> seasons
                    "s1" -> { delay(200); listOf(makeItem("e1", MediaType.EPISODE, episodeNumber = 1)) }
                    "s2" -> { delay(10); listOf(makeItem("e2", MediaType.EPISODE, episodeNumber = 2)) }
                    else -> emptyList()
                }
                return PagedResult(items = items, totalCount = items.size, hasMore = false, nextOffset = null)
            }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        // Run only current tasks (detail+seasons load, S1 episode fetch starts and hits delay(200))
        testDispatcher.scheduler.runCurrent()
        assertEquals("s1", vm.seriesState.value.selectedSeasonId)
        // Switch to S2 BEFORE S1's delay(200) completes
        vm.selectSeason("s2")
        testDispatcher.scheduler.advanceTimeBy(50)
        testDispatcher.scheduler.runCurrent()
        // S2 should be selected with correct episodes
        assertEquals("s2", vm.seriesState.value.selectedSeasonId)
        assertEquals("e2", vm.seriesState.value.episodes[0].id)
        // Advance past S1's delay - verify it doesn't overwrite
        testDispatcher.scheduler.advanceTimeBy(300)
        testDispatcher.scheduler.runCurrent()
        assertEquals("s2", vm.seriesState.value.selectedSeasonId)
        assertEquals("e2", vm.seriesState.value.episodes[0].id)
        // CancellationException was rethrown, not stored as error
        assertNull(vm.seriesState.value.episodesError)
    }

    @Test
    fun raceNonCooperativeS1DoesNotOverwriteS2() = runTest(testDispatcher) {
        // Non-cooperative fake: S1 ignores cancellation and still returns late
        val seriesItem = makeItem("series-race-nc", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1), makeItem("s2", MediaType.SEASON, seasonNumber = 2))
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                when (libraryId) {
                    "series-race-nc" -> return PagedResult(items = seasons, totalCount = seasons.size, hasMore = false, nextOffset = null)
                    "s1" -> {
                        try { delay(200) } catch (_: CancellationException) { /* ignore cancellation, still return */ }
                        return PagedResult(items = listOf(makeItem("e1", MediaType.EPISODE, episodeNumber = 1)), totalCount = 1, hasMore = false, nextOffset = null)
                    }
                    "s2" -> {
                        delay(10)
                        return PagedResult(items = listOf(makeItem("e2", MediaType.EPISODE, episodeNumber = 2)), totalCount = 1, hasMore = false, nextOffset = null)
                    }
                    else -> return PagedResult(items = emptyList(), totalCount = 0, hasMore = false, nextOffset = null)
                }
            }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        testDispatcher.scheduler.runCurrent()
        assertEquals("s1", vm.seriesState.value.selectedSeasonId)
        vm.selectSeason("s2")
        testDispatcher.scheduler.advanceTimeBy(50)
        testDispatcher.scheduler.runCurrent()
        assertEquals("s2", vm.seriesState.value.selectedSeasonId)
        assertEquals("e2", vm.seriesState.value.episodes[0].id)
        // S1's delay completes even after cancellation, but selectedSeasonId guard prevents overwrite
        testDispatcher.scheduler.advanceTimeBy(300)
        testDispatcher.scheduler.runCurrent()
        assertEquals("s2", vm.seriesState.value.selectedSeasonId)
        assertEquals("e2", vm.seriesState.value.episodes[0].id)
        assertNull(vm.seriesState.value.episodesError)
    }

    @Test
    fun seasonErrorKeepsDetailContent() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-err", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> { throw ProviderException.Network("srv", java.io.IOException("test")) }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is DetailUiState.Content)
        assertNotNull(vm.seriesState.value.seasonsError)
    }

    @Test
    fun episodeErrorKeepsSeasonSelected() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-ep-err", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                if (libraryId == "s1") throw ProviderException.Http("srv", 500, "/items", "GET", "req-1")
                return PagedResult(items = seasons, totalCount = 1, hasMore = false, nextOffset = null)
            }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("s1", vm.seriesState.value.selectedSeasonId)
        assertNotNull(vm.seriesState.value.episodesError)
    }

    @Test
    fun retryEpisodesReloadsCurrentSeason() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-retry", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        var callCount = 0
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                if (libraryId == "s1") {
                    if (callCount++ == 0) throw ProviderException.Network("srv", java.io.IOException("first fail"))
                    return PagedResult(items = listOf(makeItem("e1", MediaType.EPISODE, episodeNumber = 1)), totalCount = 1, hasMore = false, nextOffset = null)
                }
                return PagedResult(items = seasons, totalCount = 1, hasMore = false, nextOffset = null)
            }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.seriesState.value.episodesError)
        vm.retryEpisodes()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.seriesState.value.episodesError)
        assertEquals(1, vm.seriesState.value.episodes.size)
    }

    @Test
    fun libraryUnavailableState() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-no-lib", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val vm = createViewModel(detail, libraryNull = true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is DetailUiState.Content)
        assertTrue(vm.seriesState.value.libraryUnavailable)
    }

    @Test
    fun cancellationExceptionIsNotSwallowed() = runTest(testDispatcher) {
        val seriesItem = makeItem("series-cancel", MediaType.SERIES)
        val detail = MediaDetail(item = seriesItem)
        val seasons = listOf(makeItem("s1", MediaType.SEASON, seasonNumber = 1))
        val lib = object : MediaLibraryProvider {
            override suspend fun getLibraries() = emptyList<MediaLibrary>()
            override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
                if (libraryId == "s1") throw CancellationException("cancelled")
                return PagedResult(items = seasons, totalCount = 1, hasMore = false, nextOffset = null)
            }
            override suspend fun getSeasons(seriesId: String) = error("legacy")
            override suspend fun getEpisodes(seasonId: String) = error("legacy")
        }
        val vm = createViewModel(detail, libraryProvider = lib)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.seriesState.value.episodesError)
        assertTrue(vm.seriesState.value.episodes.isEmpty())
    }
}
