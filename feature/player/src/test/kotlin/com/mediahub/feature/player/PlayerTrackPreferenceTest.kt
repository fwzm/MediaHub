package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.AudioTrack
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
import com.mediahub.model.SubtitleTrack
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
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.TrackRowMap
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
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 音轨/字幕偏好（清单 6/78/82）的产品行为验收：
 * 1. 有首选字幕语言且能匹配时默认选中该字幕；
 * 2. 首选语言匹配不到时不自动开启字幕；
 * 3. 没有首选语言时保持既有行为（不主动选字幕）；
 * 4. 换集继承上一集显式选中的音频轨/字幕轨；
 * 5. 用户显式关闭字幕后，下一集不会被首选语言重新打开；
 * 6. 偏好语言随播放会话传给引擎（Media3 选择器参数）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerTrackPreferenceTest {

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
    fun `preferred subtitle language selects the matching track`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine, prefs(preferredSubtitleLanguage = "zh"))
        try {
            runCurrent()
            assertEquals(TrackSelection(0, 1, SNAPSHOT), engine.subtitleSelections.lastOrNull())
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `preferred subtitle language that matches nothing keeps subtitles off`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks(episodeSubtitles = listOf(subtitle(0, "eng"))))
        val vm = viewModel(engine, prefs(preferredSubtitleLanguage = "zh"))
        try {
            runCurrent()
            assertTrue(engine.subtitleSelections.isEmpty())
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `no preferred subtitle language leaves the existing default behaviour untouched`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine)
        try {
            runCurrent()
            assertTrue(engine.subtitleSelections.isEmpty())
            assertNull(engine.playedSession?.preferredTextLanguages?.takeIf { it.isNotEmpty() })
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `preferred track languages are carried into the playback session`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine, prefs(preferredAudioLanguage = "ja", preferredSubtitleLanguage = "zh"))
        try {
            runCurrent()
            assertEquals(listOf("ja"), engine.playedSession?.preferredAudioLanguages)
            assertEquals(listOf("zh"), engine.playedSession?.preferredTextLanguages)
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `audibly selected track is inherited by the next episode`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine)
        try {
            runCurrent()
            vm.onAudioTrackSelected(engine.uiState.value.audioTracks[1])
            emitEnded(engine, "s1e1")
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertEquals(TrackSelection(0, 1, SNAPSHOT), engine.audioSelections.lastOrNull())
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `explicitly selected subtitle is inherited by the next episode`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine)
        try {
            runCurrent()
            vm.onSubtitleTrackSelected(engine.uiState.value.subtitleTracks[1])
            emitEnded(engine, "s1e1")
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertEquals(TrackSelection(0, 1, SNAPSHOT), engine.subtitleSelections.lastOrNull())
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    @Test
    fun `explicitly disabled subtitles stay disabled on the next episode`() = runTest(dispatcher) {
        val engine = TrackEngine(twoEpisodeTracks())
        val vm = viewModel(engine, prefs(preferredSubtitleLanguage = "zh"))
        try {
            runCurrent()
            vm.onSubtitleTrackSelected(null)
            emitEnded(engine, "s1e1")
            runCurrent()

            assertEquals("s1e2", engine.playedSession?.itemId)
            assertNull(engine.subtitleSelections.last())
        } finally {
            vm.stopAndFlush(); runCurrent()
        }
    }

    // ---- fixtures ----

    private fun twoEpisodeTracks(
        episodeSubtitles: List<SubtitleTrack>? = null,
    ): Map<String, TrackFixture> {
        val audio = listOf(audio(0, "eng"), audio(1, "chi"))
        val subtitles = episodeSubtitles ?: listOf(subtitle(0, "eng"), subtitle(1, "chi"))
        return mapOf(
            "s1e1" to TrackFixture(audio, subtitles),
            "s1e2" to TrackFixture(audio, subtitles),
        )
    }

    private fun audio(index: Int, language: String) =
        AudioTrack(index = index, language = language, title = language)

    private fun subtitle(index: Int, language: String) =
        SubtitleTrack(index = index, language = language, title = language)

    private fun prefs(
        preferredAudioLanguage: String? = null,
        preferredSubtitleLanguage: String? = null,
    ) = FakeUserPreferences(
        UserPreferences(
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
        ),
    )

    private fun emitEnded(engine: TrackEngine, itemId: String) {
        engine.emitEvent(
            PlaybackEventSnapshot(
                PlaybackEvent.Ended,
                PlaybackProgress(
                    serverId = "srv-1",
                    itemId = itemId,
                    positionMs = 90_000,
                    durationMs = 90_000,
                    isPaused = false,
                    updatedAtEpochMs = 0,
                ),
            ),
        )
    }

    private fun viewModel(
        engine: TrackEngine,
        preferences: FakeUserPreferences = FakeUserPreferences(),
    ) = PlayerViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverId" to "srv-1",
                "itemId" to com.mediahub.core.common.NavArgCodec.encode("s1e1"),
            ),
        ),
        serverStore = FakeServerStore(embyServer()),
        progressStore = FakeProgressStore,
        subtitleMemoryStore = NoSubtitleMemory,
        registry = FakeRegistry(
            detail = MapDetail(episodeItems),
            playback = FakePlayback(PlaybackSource(url = "http://media/stream.mkv")),
            library = FakeLibrary(
                seasons = listOf(
                    Season(serverId = "srv-1", id = "s1", seriesId = "series-1", name = "1", seasonNumber = 1),
                ),
                episodes = mapOf(
                    "s1" to listOf(
                        Episode(serverId = "srv-1", id = "s1e1", seriesId = "series-1", seasonId = "s1", name = "1", episodeNumber = 1),
                        Episode(serverId = "srv-1", id = "s1e2", seriesId = "series-1", seasonId = "s1", name = "2", episodeNumber = 2),
                    ),
                ),
            ),
        ),
        media3EngineFactory = PlaybackEngineCreator { engine },
        mpvEngineFactory = PlaybackEngineCreator { TrackEngine(emptyMap()) },
        engineHistory = InMemoryEnginePreferenceHistory(),
        userPreferencesRepository = preferences,
        artworkPaletteLoader = NoArtworkPalette,
        logger = noOpLogger,
    )

    private data class TrackFixture(
        val audio: List<AudioTrack> = emptyList(),
        val subtitles: List<SubtitleTrack> = emptyList(),
    ) {
        fun toUiState(): PlaybackUiState = PlaybackUiState(
            audioTracks = audio,
            subtitleTracks = subtitles,
            trackRowMap = TrackRowMap(
                audio = audio.associate { it.index to TrackSelection(0, it.index, SNAPSHOT) },
                subtitle = subtitles.associate { it.index to TrackSelection(0, it.index, SNAPSHOT) },
                snapshotToken = SNAPSHOT,
            ),
        )
    }

    private class FakeUserPreferences(
        initial: UserPreferences = UserPreferences(),
    ) : UserPreferencesRepository {
        val state = MutableStateFlow(initial)
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class TrackEngine(
        private val fixtures: Map<String, TrackFixture>,
    ) : PlaybackEnginePort {
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 8)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        private val eventsFlow = MutableSharedFlow<PlaybackEventMessage>(extraBufferCapacity = 8)
        override val events: Flow<PlaybackEventMessage> = flow {
            eventsFlow.collect { emit(it) }
        }
        override val kind: EngineKind = EngineKind.MEDIA3
        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> =
            MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        var playedSession: PlaybackSession? = null
        val audioSelections = mutableListOf<TrackSelection?>()
        val subtitleSelections = mutableListOf<TrackSelection?>()

        override fun attachSurface(surface: android.view.Surface?) = Unit

        override fun play(session: PlaybackSession) {
            playedSession = session
            ui.value = fixtures[session.itemId]?.toUiState() ?: PlaybackUiState()
        }

        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) {
            audioSelections += selection
        }

        override fun selectSubtitleTrack(selection: TrackSelection?) {
            subtitleSelections += selection
        }

        fun emitEvent(event: PlaybackEventMessage) {
            eventsFlow.tryEmit(event)
        }

        override fun stop(): PlaybackProgress? = null
        override fun release() = Unit
    }

    private object NoArtworkPalette : ArtworkPaletteLoader {
        override suspend fun load(artworkKey: String, url: String?) = null
    }

    private object NoSubtitleMemory : com.mediahub.core.database.repository.SubtitleMemoryStore {
        override suspend fun recall(versionKey: String) = null
        override suspend fun remember(entry: com.mediahub.core.database.repository.SubtitleMemoryEntry) = Unit
        override suspend fun forget(versionKey: String) = Unit
    }

    private object FakeProgressStore : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
    }

    private class FakeServerStore(private val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
        override suspend fun updateServer(server: MediaServer) = Unit
        override suspend fun setDefault(id: String) = Unit
    }

    private fun embyServer() = MediaServer(
        id = "srv-1",
        name = "Test",
        type = ServerType.EMBY,
        createdAtEpochMs = 0L,
    )

    private val episodeItems: Map<String, MediaItem> = listOf("s1e1", "s1e2").associateWith { id ->
        MediaItem(
            serverId = "srv-1",
            id = id,
            type = MediaType.EPISODE,
            title = id,
            seriesId = "series-1",
            seasonId = "s1",
            seasonNumber = 1,
            episodeNumber = id.takeLast(1).toIntOrNull(),
        )
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
        private val source: PlaybackSource,
    ) : MediaPlaybackProvider {
        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource = source
    }

    private class FakeProvider(override val serverId: String) : MediaProvider {
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "emby",
            serverType = ServerType.EMBY,
            displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(
                ProviderCapability.AUTH,
                ProviderCapability.LIBRARY,
                ProviderCapability.DETAIL,
                ProviderCapability.PLAYBACK,
            ),
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
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = FakeProvider(server.id),
            library = library,
            detail = detail,
            playback = playback,
        )
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val SNAPSHOT = 42L
    }
}
