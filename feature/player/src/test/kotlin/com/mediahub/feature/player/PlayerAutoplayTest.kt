package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.Episode
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.InMemoryEnginePreferenceHistory
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackEventMessage
import com.mediahub.player.engine.PlaybackEventSnapshot
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A3 播完自动连播（autoPlayNextEpisode）的产品行为验收。
 *
 * 钉住的规则：
 * 1. 开关关着时，Ended 之后仍停在刚播完的条目上（不排队、不切换）；
 * 2. 同季有下一集就播下一集，本季最后一集播完则跨季播下一季第一集；
 * 3. 最后一季最后一集播完不切换、也不报错；
 * 4. 切换失败一次即停住（不无限重试、不连环跳集）；
 * 5. 切集后进度上报的身份是新条目（itemId 已改写）；
 * 6. 同一集的重复 Ended 只安排一次；
 * 7. 电影播完不触发连播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerAutoplayTest {
    @Test fun initialCriticalSnapshotIsNotDroppedBeforeTheSyncSubscriberIsReady() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val saved = mutableListOf<PlaybackProgress>()
        val initial = PlaybackEventSnapshot(PlaybackEvent.Paused, PlaybackProgress(
            serverId = "srv-1", itemId = "s1e1", positionMs = 20_000,
            durationMs = 90_000, isPaused = true, updatedAtEpochMs = 100,
        ))
        val engine = FakeEngine(initialEvent = initial)
        val vm = viewModel(twoEpisodeSeries(), engine, library(),
            progressStore = FakeProgressStore(null) { saved += it })
        try {
            runCurrent()
            assertEquals(listOf(20_000L), saved.map { it.positionMs })
        } finally { vm.stopAndFlush(); runCurrent() }
    }

    @Test fun staleEndedSnapshotCannotAdvanceTheCurrentlyPlayingEpisode() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = viewModel(twoEpisodeSeries(), engine, library())
        try {
            runCurrent()
            engine.emitEvent(PlaybackEventSnapshot(PlaybackEvent.Ended, PlaybackProgress(
                serverId = "srv-1", itemId = "previous-item", positionMs = 90_000,
                durationMs = 90_000, isPaused = false, updatedAtEpochMs = 100,
            )))
            runCurrent()
            assertEquals("s1e1", engine.playedSession?.itemId)
            assertEquals(1, engine.playCalls)
        } finally { vm.stopAndFlush(); runCurrent() }
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with autoplay off the finished episode stays put`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val prefs = FakeUserPreferences().apply {
            state.value = UserPreferences(autoPlayNextEpisode = false)
        }
        val vm = viewModel(twoEpisodeSeries(), engine, library(), preferences = prefs)
        try {
            runCurrent()
            assertEquals("s1e1", engine.playedSession?.itemId)

            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("s1e1", engine.playedSession?.itemId)
            assertEquals(1, engine.playCalls)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `autoplay continues with the next episode in the same season`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = viewModel(twoEpisodeSeries(), engine, library())
        try {
            runCurrent()
            assertEquals("s1e1", engine.playedSession?.itemId)

            engine.emitEvent(PlaybackEventSnapshot(PlaybackEvent.Ended, PlaybackProgress(
                serverId = "srv-1", itemId = "s1e1", positionMs = 90_000,
                durationMs = 90_000, isPaused = false, updatedAtEpochMs = 100,
            )))
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertEquals(MediaType.EPISODE, engine.playedSession?.itemType)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `autoplay crosses into the first episode of the following season`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val items = mapOf(
            "s1e1" to episodeItem("s1e1", "s1", seasonNumber = 1, episodeNumber = 1),
            "s2e1" to episodeItem("s2e1", "s2", seasonNumber = 2, episodeNumber = 1),
        )
        val library = FakeLibrary(
            seasons = listOf(season("s1", 1), season("s2", 2)),
            episodes = mapOf(
                "s1" to listOf(episodeRecord("s1e1", "s1", 1)),
                "s2" to listOf(episodeRecord("s2e1", "s2", 1), episodeRecord("s2e2", "s2", 2)),
            ),
        )
        val vm = viewModel(items, engine, library)
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("s2e1", engine.playedSession?.itemId)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `the last episode of the last season does not switch and does not fail`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val items = mapOf("s2e1" to episodeItem("s2e1", "s2", seasonNumber = 2, episodeNumber = 1))
        val library = FakeLibrary(
            seasons = listOf(season("s1", 1), season("s2", 2)),
            episodes = mapOf(
                "s1" to listOf(episodeRecord("s1e1", "s1", 1)),
                "s2" to listOf(episodeRecord("s2e1", "s2", 1)),
            ),
        )
        val vm = viewModel(items, engine, library, startItemId = "s2e1")
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("s2e1", engine.playedSession?.itemId)
            assertEquals(1, engine.playCalls)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `a failed switch stops autoplay instead of retrying forever`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = viewModel(
            items = twoEpisodeSeries(),
            engine = engine,
            library = library(),
            playback = FailingSecondPlayback(firstItemId = "s1e1"),
        )
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertTrue(vm.resolveState.value is ResolveState.Failed)
            assertEquals(1, engine.playCalls)

            // 失败一次后本轮播放彻底停住：再来 Ended 也不会继续尝试。
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()
            assertEquals(1, engine.playCalls)
            assertTrue(vm.resolveState.value is ResolveState.Failed)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `progress reported after a switch carries the new item identity`() = runTest(dispatcher) {
        val saved = mutableListOf<PlaybackProgress>()
        val progressStore = FakeProgressStore(resume = null) { saved += it }
        val engine = FakeEngine()
        val vm = viewModel(
            items = twoEpisodeSeries(),
            engine = engine,
            library = library(),
            progressStore = progressStore,
        )
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()
            assertEquals("s1e2", engine.playedSession?.itemId)

            saved.clear()
            engine.emitProgress(
                PlaybackProgress(
                    serverId = "srv-1",
                    itemId = "s1e2",
                    positionMs = 30_000,
                    durationMs = 1_500_000,
                    isPaused = false,
                    updatedAtEpochMs = 1,
                ),
            )
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(saved.isNotEmpty())
            assertEquals("s1e2", saved.last().itemId)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `the same episode ending twice only advances one episode`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = viewModel(twoEpisodeSeries(), engine, library())
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertEquals(2, engine.playCalls)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `autoplay also works when the player was opened from a detail page snapshot`() = runTest(dispatcher) {
        val engine = FakeEngine()
        // 快照只有 type/title：seriesId/seasonId/集号都要靠 detail 补齐，否则连播会静默失效。
        val vm = viewModel(twoEpisodeSeries(), engine, library(), startType = "EPISODE")
        try {
            runCurrent()
            assertEquals("s1e1", engine.playedSession?.itemId)

            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `a movie ending never starts anything else`() = runTest(dispatcher) {
        val engine = FakeEngine()
        val items = mapOf(
            "m1" to MediaItem(
                serverId = "srv-1", id = "m1", type = MediaType.MOVIE,
                title = "电影A", container = "mkv",
            ),
        )
        val vm = viewModel(items, engine, library(), startItemId = "m1")
        try {
            runCurrent()
            engine.emitEvent(PlaybackEvent.Ended)
            runCurrent()

            assertEquals("m1", engine.playedSession?.itemId)
            assertEquals(1, engine.playCalls)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- helpers ----

    /** 详情页直传启动快照时 type 非空，播放器会跳过 detail 拉取（见 PlayerViewModel.resolveInternal）。 */
    private fun savedState(itemId: String, type: String = ""): SavedStateHandle {
        val state = mutableMapOf<String, Any>(
            "serverId" to "srv-1",
            "itemId" to NavArgCodec.encode(itemId),
            "title" to "剧集A",
        )
        if (type.isNotEmpty()) state["type"] = type
        return SavedStateHandle(state)
    }

    private fun embyServer() = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost:8096", createdAtEpochMs = 1L,
    )

    private fun twoEpisodeSeries() = mapOf(
        "s1e1" to episodeItem("s1e1", "s1", seasonNumber = 1, episodeNumber = 1),
        "s1e2" to episodeItem("s1e2", "s1", seasonNumber = 1, episodeNumber = 2),
    )

    private fun library() = FakeLibrary(
        seasons = listOf(season("s1", 1)),
        episodes = mapOf("s1" to listOf(episodeRecord("s1e1", "s1", 1), episodeRecord("s1e2", "s1", 2))),
    )

    private fun episodeItem(id: String, seasonId: String, seasonNumber: Int, episodeNumber: Int) = MediaItem(
        serverId = "srv-1",
        id = id,
        type = MediaType.EPISODE,
        title = episodeNumber.toString(),
        seriesId = "series-1",
        seasonId = seasonId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        container = "mkv",
    )

    private fun season(id: String, number: Int) = Season(
        serverId = "srv-1", id = id, seriesId = "series-1", name = number.toString(), seasonNumber = number,
    )

    private fun episodeRecord(id: String, seasonId: String, number: Int) = Episode(
        serverId = "srv-1", id = id, seriesId = "series-1", seasonId = seasonId,
        name = number.toString(), episodeNumber = number,
    )

    private fun viewModel(
        items: Map<String, MediaItem>,
        engine: FakeEngine,
        library: MediaLibraryProvider?,
        playback: MediaPlaybackProvider = FakePlayback(PlaybackSource(url = "http://media/stream.mkv")),
        progressStore: FakeProgressStore = FakeProgressStore(resume = null),
        preferences: FakeUserPreferences = FakeUserPreferences(),
        startItemId: String = "s1e1",
        startType: String = "",
    ) = PlayerViewModel(
        savedStateHandle = savedState(startItemId, startType),
        serverStore = FakeServerStore(embyServer()),
        progressStore = progressStore,
        subtitleMemoryStore = NoSubtitleMemory,
        registry = FakeRegistry(
            detail = MapDetail(items),
            playback = playback,
            library = library,
        ),
        media3EngineFactory = PlaybackEngineCreator { engine },
        mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
        engineHistory = InMemoryEnginePreferenceHistory(),
        userPreferencesRepository = preferences,
        artworkPaletteLoader = NoArtworkPalette,
        logger = noOpLogger,
    )

    // ---- fakes（与 PlayerViewModelTest 同构；fakes 为 private，不能跨文件复用）----

    private val NoArtworkPalette = ArtworkPaletteLoader { _, _ -> null }

    private val NoSubtitleMemory = object : com.mediahub.core.database.repository.SubtitleMemoryStore {
        override suspend fun recall(versionKey: String) = null
        override suspend fun remember(entry: com.mediahub.core.database.repository.SubtitleMemoryEntry) = Unit
        override suspend fun forget(versionKey: String) = Unit
    }

    private class FakeUserPreferences : UserPreferencesRepository {
        val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class FakeServerStore(private val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
        override suspend fun updateServer(server: MediaServer) { }
        override suspend fun setDefault(id: String) { }
    }

    private class FakeProgressStore(
        private val resume: Long?,
        private val onSave: (PlaybackProgress) -> Unit = {},
    ) : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = resume
        override suspend fun save(progress: PlaybackProgress) = onSave(progress)
    }

    private class MapDetail(private val items: Map<String, MediaItem>) : MediaDetailProvider {
        override suspend fun getItemDetail(itemId: String): MediaDetail = MediaDetail(
            item = items[itemId] ?: MediaItem(
                serverId = "srv-1",
                id = itemId,
                type = MediaType.OTHER,
                title = itemId,
            ),
        )
    }

    private class FakeLibrary(
        private val seasons: List<Season>,
        private val episodes: Map<String, List<Episode>>,
    ) : MediaLibraryProvider {
        override suspend fun getLibraries(): List<MediaLibrary> = emptyList()
        override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
            PagedResult(items = emptyList())
        override suspend fun getSeasons(seriesId: String): List<Season> = seasons
        override suspend fun getEpisodes(seasonId: String): List<Episode> = episodes[seasonId].orEmpty()
    }

    private class FakePlayback(
        private val source: PlaybackSource? = null,
        private val error: ProviderException? = null,
    ) : MediaPlaybackProvider {
        var receivedItem: MediaItem? = null
        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
            receivedItem = item
            error?.let { throw it }
            return checkNotNull(source)
        }
    }

    /** 第一集能正常解析；任何后续集都抛错，用来验证「切换失败即停住」。 */
    private class FailingSecondPlayback(private val firstItemId: String) : MediaPlaybackProvider {
        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
            if (item.id != firstItemId) {
                throw ProviderException.NotYetImplemented("srv-1", "第二集不可播")
            }
            return PlaybackSource(url = "http://media/stream.mkv")
        }
    }

    private class FakeProvider(override val serverId: String) : MediaProvider {
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "emby",
            serverType = ServerType.EMBY,
            displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.AUTH, ProviderCapability.LIBRARY,
                ProviderCapability.DETAIL, ProviderCapability.PLAYBACK),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeRegistry(
        private val detail: MediaDetailProvider? = null,
        private val playback: MediaPlaybackProvider? = null,
        private val library: MediaLibraryProvider? = null,
    ) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = FakeProvider(server.id),
            library = library,
            detail = detail,
            playback = playback,
        )
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class FakeEngine(
        private val finalProgress: PlaybackProgress? = null,
        private val initialEvent: PlaybackEventMessage? = null,
    ) : PlaybackEnginePort {
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 8)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        private val eventsFlow = MutableSharedFlow<PlaybackEventMessage>(extraBufferCapacity = 8)
        override val events: Flow<PlaybackEventMessage> get() = kotlinx.coroutines.flow.flow {
            initialEvent?.let { emit(it) }
            eventsFlow.collect { emit(it) }
        }
        var playedSession: PlaybackSession? = null
        var playCalls = 0
        override val kind: EngineKind = EngineKind.MEDIA3
        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> = MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        override fun attachSurface(surface: android.view.Surface?) = Unit
        override fun play(session: PlaybackSession) {
            playCalls += 1
            playedSession = session
        }
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: com.mediahub.player.engine.SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        override fun selectSubtitleTrack(selection: TrackSelection?) = Unit
        fun emitProgress(value: PlaybackProgress) { progressFlow.tryEmit(value) }
        fun emitEvent(event: PlaybackEventMessage) { eventsFlow.tryEmit(event) }
        override fun stop(): PlaybackProgress? = finalProgress
        override fun release() = Unit
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
