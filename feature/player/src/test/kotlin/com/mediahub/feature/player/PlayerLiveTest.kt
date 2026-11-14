package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import com.mediahub.player.engine.InMemoryEnginePreferenceHistory
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 直播播放路径（条目 112）：直播必须走独立的起播/切台语义。
 *
 * - 起播不读续播位置（resume=0），也不得影响点播的续播恢复；
 * - 退出/切台不写本地进度、不向 Provider 上报直播进度；
 * - 直播重新解析（失败重试/切台重建）前先停掉旧引擎，避免新状态叠加在旧画面上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLiveTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedState(type: String) = SavedStateHandle(
        mapOf(
            "serverId" to SERVER_ID,
            "itemId" to NavArgCodec.encode(CHANNEL_ID),
            "title" to "CCTV-1",
            "type" to type,
        )
    )

    @Test
    fun `live channel starts from zero and ignores stored resume`() = runTest(dispatcher) {
        val playback = FakePlayback()
        val engine = FakeEngine()
        val vm = newViewModel(savedState(MediaType.LIVE_TV.name), playback, engine)

        try {
            runCurrent()

            assertTrue(vm.isLivePlayback)
            assertEquals(0L, playback.receivedStartMs)
            assertEquals(0L, engine.playedSession?.resumePositionMs)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `live exit never persists nor reports progress`() = runTest(dispatcher) {
        val finalProgress = PlaybackProgress(
            serverId = SERVER_ID,
            itemId = CHANNEL_ID,
            positionMs = 42_000,
            durationMs = 0,
            isPaused = true,
            updatedAtEpochMs = 1,
        )
        val engine = FakeEngine(finalProgress = finalProgress)
        val saved = mutableListOf<PlaybackProgress>()
        val vm = newViewModel(savedState(MediaType.LIVE_TV.name), FakePlayback(), engine) { saved += it }

        try {
            runCurrent()
            engine.emitProgress(finalProgress.copy(positionMs = 5_000))
            advanceTimeBy(6_000)
            runCurrent()

            vm.stopAndFlush()
            runCurrent()

            assertTrue("live playback must not save local progress", saved.isEmpty())
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `live re-resolve stops previous stream before the new source resolves`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val playback = FakePlayback()
        val engine = FakeEngine()
        val vm = newViewModel(savedState(MediaType.LIVE_TV.name), playback, engine)

        try {
            runCurrent()
            assertEquals(0, engine.stopCalls)
            assertTrue(engine.playedSession != null)

            playback.gate = gate
            vm.resolve()
            runCurrent()
            // 新源仍在解析（gate 未放行）：旧直播流必须先停掉，不能继续播在加载层下面。
            assertEquals("old live stream must stop while the next one resolves", 1, engine.stopCalls)

            gate.complete(Unit)
            runCurrent()
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            gate.complete(Unit)
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `vod playback still restores resume position`() = runTest(dispatcher) {
        val playback = FakePlayback()
        val vm = newViewModel(savedState(MediaType.MOVIE.name), playback, FakeEngine())

        try {
            runCurrent()

            assertFalse(vm.isLivePlayback)
            assertEquals(RESUME_MS, playback.receivedStartMs)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    private fun newViewModel(
        handle: SavedStateHandle,
        playback: FakePlayback,
        engine: FakeEngine,
        onSave: (PlaybackProgress) -> Unit = {},
    ) = PlayerViewModel(
        savedStateHandle = handle,
        serverStore = FakeServerStore(),
        progressStore = FakeProgressStore(onSave = onSave),
        subtitleMemoryStore = NoSubtitleMemory,
        registry = FakeRegistry(playback),
        media3EngineFactory = PlaybackEngineCreator { engine },
        mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
        engineHistory = InMemoryEnginePreferenceHistory(),
        userPreferencesRepository = FakeUserPreferences(),
        artworkPaletteLoader = ArtworkPaletteLoader { _, _ -> null },
        logger = NoOpLogger,
    )

    private companion object {
        const val SERVER_ID = "iptv-1"
        const val CHANNEL_ID = "cctv1"
        const val RESUME_MS = 15_000L
    }

    private val NoSubtitleMemory = object : com.mediahub.core.database.repository.SubtitleMemoryStore {
        override suspend fun recall(versionKey: String) = null
        override suspend fun remember(entry: com.mediahub.core.database.repository.SubtitleMemoryEntry) = Unit
        override suspend fun forget(versionKey: String) = Unit
    }

    private object NoOpLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private class FakeUserPreferences : UserPreferencesRepository {
        private val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class FakeServerStore : ServerStore {
        private val server = MediaServer(
            id = SERVER_ID,
            name = "IPTV",
            type = ServerType.IPTV,
            baseUrl = "http://fixture.invalid/live.m3u",
            createdAtEpochMs = 1L,
        )

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
        override suspend fun updateServer(server: MediaServer) = Unit
        override suspend fun setDefault(id: String) = Unit
    }

    private class FakeProgressStore(
        private val onSave: (PlaybackProgress) -> Unit,
    ) : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = RESUME_MS
        override suspend fun save(progress: PlaybackProgress) = onSave(progress)
    }

    private class FakePlayback : MediaPlaybackProvider {
        var receivedStartMs: Long? = null

        /** 非空时挂起解析（用于观察「解析中」的中间态）。 */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
            receivedStartMs = options.startPositionMs
            gate?.await()
            return PlaybackSource(url = "http://fixture.invalid/live/index.m3u8")
        }
    }

    private class FakeDetail : MediaDetailProvider {
        override suspend fun getItemDetail(itemId: String): MediaDetail = MediaDetail(
            item = MediaItem(
                serverId = SERVER_ID,
                id = itemId,
                type = MediaType.LIVE_TV,
                title = "CCTV-1",
            ),
        )
    }

    private class FakeProvider : MediaProvider {
        override val serverId: String = SERVER_ID
        override val type: ServerType = ServerType.IPTV
        override val displayName: String = "IPTV"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "iptv",
            serverType = ServerType.IPTV,
            displayName = "IPTV",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(
                ProviderCapability.LIBRARY,
                ProviderCapability.DETAIL,
                ProviderCapability.PLAYBACK,
            ),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )

        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeRegistry(private val playback: MediaPlaybackProvider) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = setOf(ServerType.IPTV)
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = FakeProvider(),
            detail = FakeDetail(),
            playback = playback,
        )
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class FakeEngine(
        private val finalProgress: PlaybackProgress? = null,
    ) : PlaybackEnginePort {
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        private val eventsFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        override val events: Flow<PlaybackEvent> get() = eventsFlow
        var playedSession: PlaybackSession? = null
        var playCalls = 0
        var stopCalls = 0
        override val kind = com.mediahub.player.engine.EngineKind.MEDIA3
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
        fun emitProgress(value: PlaybackProgress) {
            progressFlow.tryEmit(value)
        }
        override fun stop(): PlaybackProgress? {
            stopCalls += 1
            return finalProgress
        }
        override fun release() = Unit
    }
}
