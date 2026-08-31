package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.ui.effects.VisualPalette
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
import com.mediahub.player.engine.EngineKind
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProgressProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * PlayerViewModel 解析链路测试（Phase 1B-2.1）。
 *
 * 验收（任务书）：A. 无扩展名 itemId 时，detail 返回的真实 MediaType（MOVIE）覆盖
 * MediaTypeGuesser fallback，并传给 playbackProvider；B. playbackProvider 返回的
 * PlaybackSource 最终到达 engine.play。不测试 Media3 内部。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun savedState(itemId: String) = SavedStateHandle(
        mapOf(
            "serverId" to "srv-1",
            "itemId" to NavArgCodec.encode(itemId),
            "title" to "电影A",
        )
    )

    private fun embyServer() = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost:8096", createdAtEpochMs = 1L,
    )

    // ---- A：detail 真实类型覆盖 fallback ----
    @Test
    fun `detail media type wins over MediaTypeGuesser fallback`() = runTest(dispatcher) {
        val playback = FakePlayback(PlaybackSource(url = "http://media/stream.mkv"))
        val registry = FakeRegistry(
            detail = FakeDetail(
                MediaItem(
                    serverId = "srv-1", id = "m1", type = MediaType.MOVIE,
                    title = "电影A", container = "mkv",
                )
            ),
            playback = playback,
        )
        val engine = FakeEngine()
        val vm = PlayerViewModel(
            savedStateHandle = savedState("m1"), // 无文件扩展名：Guesser fallback 会是 OTHER
            serverStore = FakeServerStore(embyServer()),
            progressStore = FakeProgressStore(resume = 15_000L),
            registry = registry,
            media3EngineFactory = PlaybackEngineCreator { engine },
            mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
            engineHistory = InMemoryEnginePreferenceHistory(),
            userPreferencesRepository = FakeUserPreferences(),
            artworkPaletteLoader = NoArtworkPalette,
            logger = noOpLogger,
        )
        try {
            runCurrent()

            // playbackProvider 收到的是 detail 返回的 MOVIE，而不是 fallback 的 OTHER
            val received = playback.receivedItem
            assertEquals(MediaType.MOVIE, received?.type)
            assertEquals("m1", received?.id)
            // 续播位置透传
            assertEquals(15_000L, playback.receivedStartMs)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            // 终止 ProgressSyncCoordinator 的 sample 长任务，避免 Gradle test worker 泄漏。
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- B：PlaybackSource 最终到达 engine.play ----
    @Test
    fun `resolved playback source reaches engine play`() = runTest(dispatcher) {
        val source = PlaybackSource(
            url = "http://media/stream.mkv",
            headers = mapOf("X-Emby-Token" to "tok-1"),
            container = "mkv",
        )
        val playback = FakePlayback(source)
        val registry = FakeRegistry(
            detail = FakeDetail(
                MediaItem(
                    serverId = "srv-1", id = "m1", type = MediaType.MOVIE,
                    title = "电影A", container = "mkv",
                )
            ),
            playback = playback,
        )
        val engine = FakeEngine()
        val vm = PlayerViewModel(
            savedStateHandle = savedState("m1"),
            serverStore = FakeServerStore(embyServer()),
            progressStore = FakeProgressStore(resume = null),
            registry = registry,
            media3EngineFactory = PlaybackEngineCreator { engine },
            mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
            engineHistory = InMemoryEnginePreferenceHistory(),
            userPreferencesRepository = FakeUserPreferences(),
            artworkPaletteLoader = NoArtworkPalette,
            logger = noOpLogger,
        )
        try {
            runCurrent()

            val session = engine.playedSession
            assertEquals(source, session?.source)
            assertEquals("m1", session?.itemId)
            assertEquals(ResolveState.Ready, vm.resolveState.value)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- C：解析失败 → Failed，engine 不 play ----
    @Test
    fun `playback failure surfaces as failed state without engine play`() = runTest(dispatcher) {
        val registry = FakeRegistry(
            detail = FakeDetail(
                MediaItem(
                    serverId = "srv-1", id = "m1", type = MediaType.MOVIE,
                    title = "电影A",
                )
            ),
            playback = FakePlayback(error = ProviderException.NotYetImplemented("srv-1", "当前媒体需要转码")),
        )
        val engine = FakeEngine()
        val vm = PlayerViewModel(
            savedStateHandle = savedState("m1"),
            serverStore = FakeServerStore(embyServer()),
            progressStore = FakeProgressStore(resume = null),
            registry = registry,
            media3EngineFactory = PlaybackEngineCreator { engine },
            mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
            engineHistory = InMemoryEnginePreferenceHistory(),
            userPreferencesRepository = FakeUserPreferences(),
            artworkPaletteLoader = NoArtworkPalette,
            logger = noOpLogger,
        )
        try {
            runCurrent()

            assertTrue(vm.resolveState.value is ResolveState.Failed)
            assertNull(engine.playedSession)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `artwork palette succeeds preserves prior value on failure and rejects late result`() =
        runTest(dispatcher) {
            val detail = SequencedDetail(
                listOf(
                    mediaItemWithPoster("https://image/one"),
                    mediaItemWithPoster("https://image/fails"),
                    mediaItemWithPoster("https://image/late"),
                    mediaItemWithPoster("https://image/latest"),
                ),
            )
            val registry = FakeRegistry(
                detail = detail,
                playback = FakePlayback(PlaybackSource(url = "http://media/stream.mkv")),
            )
            val loader = ControllableArtworkPaletteLoader()
            val vm = PlayerViewModel(
                savedStateHandle = savedState("m1"),
                serverStore = FakeServerStore(embyServer()),
                progressStore = FakeProgressStore(resume = null),
                registry = registry,
                media3EngineFactory = PlaybackEngineCreator { FakeEngine() },
                mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
                engineHistory = InMemoryEnginePreferenceHistory(),
                userPreferencesRepository = FakeUserPreferences(),
                artworkPaletteLoader = loader,
                logger = noOpLogger,
            )
            try {
                runCurrent()
                val first = palette(0xFF446688.toInt())
                loader.complete("https://image/one", first)
                runCurrent()
                assertEquals(first, vm.artworkPalette.value)

                vm.resolve()
                runCurrent()
                loader.complete("https://image/fails", null)
                runCurrent()
                assertEquals(first, vm.artworkPalette.value)

                vm.resolve()
                runCurrent()
                vm.resolve()
                runCurrent()
                val latest = palette(0xFFAA7744.toInt())
                loader.complete("https://image/latest", latest)
                runCurrent()
                assertEquals(latest, vm.artworkPalette.value)

                loader.complete("https://image/late", palette(0xFF22AA66.toInt()))
                runCurrent()
                assertEquals(latest, vm.artworkPalette.value)
            } finally {
                vm.stopAndFlush()
                runCurrent()
            }
        }

    @Test
    fun `exit cancels periodic reports before final report and releases only after final completes`() =
        runTest(dispatcher) {
            val calls = mutableListOf<String>()
            val periodicPositions = mutableListOf<Long>()
            val finalEntered = CompletableDeferred<Unit>()
            val finishFinal = CompletableDeferred<Unit>()
            val finalProgress = PlaybackProgress(
                serverId = "srv-1", itemId = "m1", positionMs = 12_000,
                durationMs = 60_000, isPaused = true, updatedAtEpochMs = 1,
                sessionId = "visual-integration-session",
            )
            val progressProvider = object : MediaProgressProvider {
                override val remoteReportIntervalMs = 100L
                override suspend fun reportProgress(progress: PlaybackProgress) {
                    calls += "periodic"
                    periodicPositions += progress.positionMs
                }
                override suspend fun reportFinalProgress(progress: PlaybackProgress) {
                    assertEquals(finalProgress, progress)
                    calls += "final"
                    finalEntered.complete(Unit)
                    finishFinal.await()
                }
                override suspend fun getContinueWatching(limit: Int) = emptyList<MediaItem>()
                override suspend fun getResumePosition(itemId: String): Long? = null
            }
            val engine = FakeEngine(
                finalProgress = finalProgress,
                onStop = { calls += "stop" },
                onRelease = { calls += "release" },
            )
            val vm = PlayerViewModel(
                savedStateHandle = savedState("m1"),
                serverStore = FakeServerStore(embyServer()),
                progressStore = FakeProgressStore(resume = null) { calls += "local" },
                registry = FakeRegistry(
                    detail = FakeDetail(mediaItemWithPoster("https://image/one")),
                    playback = FakePlayback(PlaybackSource(
                        url = "http://media/stream.mkv", sessionId = finalProgress.sessionId,
                    )),
                    progress = progressProvider,
                ),
                media3EngineFactory = PlaybackEngineCreator { engine },
                mpvEngineFactory = PlaybackEngineCreator { FakeEngine() },
                engineHistory = InMemoryEnginePreferenceHistory(),
                userPreferencesRepository = FakeUserPreferences(),
                artworkPaletteLoader = NoArtworkPalette,
                logger = noOpLogger,
            )
            try {
                runCurrent()
                // Positive control: prove the production coordinator was subscribed and could
                // report before asserting that exit cancels that previously live pipeline.
                engine.emitProgress(finalProgress.copy(positionMs = 9_000))
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(listOf("periodic"), calls)
                assertEquals(listOf(9_000L), periodicPositions)
                calls.clear()
                engine.emitProgress(finalProgress.copy(positionMs = 10_000))
                runCurrent() // Queue a periodic sample before exit begins.
                val exit = launch { vm.stopAndFlush() }
                runCurrent()
                assertTrue("final path must have started", finalEntered.isCompleted)
                assertEquals(listOf("stop", "local", "final"), calls)

                // While final reporting is suspended, neither an already queued sample nor a
                // late engine emission may reopen the remote playing session.
                engine.emitProgress(finalProgress.copy(positionMs = 13_000))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(listOf("stop", "local", "final"), calls)
                finishFinal.complete(Unit)
                exit.join()
                vm.stopAndFlush() // Explicit Back and onDispose must remain idempotent.
                assertEquals(listOf("stop", "local", "final", "release"), calls)
            } finally {
                finishFinal.complete(Unit)
                vm.stopAndFlush()
                runCurrent()
            }
        }

    // ---- fakes ----
    private val NoArtworkPalette = ArtworkPaletteLoader { _, _ -> null }

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
    }

    private class FakeProgressStore(
        private val resume: Long?,
        private val onSave: (PlaybackProgress) -> Unit = {},
    ) : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = resume
        override suspend fun save(progress: PlaybackProgress) = onSave(progress)
    }

    private class FakeDetail(private val item: MediaItem) : MediaDetailProvider {
        override suspend fun getItemDetail(itemId: String): MediaDetail = MediaDetail(item = item)
    }

    private class SequencedDetail(items: List<MediaItem>) : MediaDetailProvider {
        private val remaining = ArrayDeque(items)
        private var last = items.last()

        override suspend fun getItemDetail(itemId: String): MediaDetail {
            if (remaining.isNotEmpty()) last = remaining.removeFirst()
            return MediaDetail(item = last)
        }
    }

    private class ControllableArtworkPaletteLoader : ArtworkPaletteLoader {
        private val pending = mutableMapOf<String, CompletableDeferred<VisualPalette?>>()

        override suspend fun load(artworkKey: String, url: String?): VisualPalette? {
            val requiredUrl = checkNotNull(url)
            return pending.getOrPut(requiredUrl) { CompletableDeferred() }.await()
        }

        fun complete(url: String, value: VisualPalette?) {
            pending.getOrPut(url) { CompletableDeferred() }.complete(value)
        }
    }

    private class FakePlayback(
        private val source: PlaybackSource? = null,
        private val error: ProviderException? = null,
    ) : MediaPlaybackProvider {
        var receivedItem: MediaItem? = null
        var receivedStartMs: Long? = null
        override suspend fun resolvePlayback(
            item: MediaItem,
            options: PlaybackOptions,
        ): PlaybackSource {
            receivedItem = item
            receivedStartMs = options.startPositionMs
            error?.let { throw it }
            return checkNotNull(source)
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
            declaredCapabilities = setOf(ProviderCapability.AUTH, ProviderCapability.LIBRARY),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeRegistry(
        private val detail: MediaDetailProvider? = null,
        private val playback: MediaPlaybackProvider? = null,
        private val progress: MediaProgressProvider? = null,
    ) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle =
            ProviderHandle(provider = FakeProvider(server.id), detail = detail, playback = playback, progress = progress)
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class FakeEngine(
        private val finalProgress: PlaybackProgress? = null,
        private val onStop: () -> Unit = {},
        private val onRelease: () -> Unit = {},
    ) : PlaybackEnginePort {
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        private val progressFlow = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1)
        override val progress: SharedFlow<PlaybackProgress> get() = progressFlow
        private val eventsFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        override val events: Flow<PlaybackEvent> get() = eventsFlow
        var playedSession: PlaybackSession? = null
        override val kind: EngineKind = EngineKind.MEDIA3
        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> = MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        override fun attachSurface(surface: android.view.Surface?) = Unit
        override fun play(session: PlaybackSession) { playedSession = session }
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: com.mediahub.player.engine.SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        override fun selectSubtitleTrack(selection: TrackSelection?) = Unit
        fun emitProgress(value: PlaybackProgress) { progressFlow.tryEmit(value) }
        override fun stop(): PlaybackProgress? {
            onStop()
            return finalProgress
        }
        override fun release() = onRelease()
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun mediaItemWithPoster(url: String) = MediaItem(
        serverId = "srv-1",
        id = "m1",
        type = MediaType.MOVIE,
        title = "电影A",
        posterUrl = url,
        container = "mkv",
    )

    private fun palette(primary: Int) = VisualPalette(
        background = 0xFF080A10.toInt(),
        primary = primary,
        secondary = 0xFF334455.toInt(),
        accent = 0xFFCCDDEE.toInt(),
    )
}
