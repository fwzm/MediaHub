package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.database.repository.SubtitleMemoryEntry
import com.mediahub.core.database.repository.SubtitleMemoryKeys
import com.mediahub.core.database.repository.SubtitleMemoryStore
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
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.EnginePreferenceHistory
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.InMemoryEnginePreferenceHistory
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.SubtitleCapabilities
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.DiscoveredSubtitle
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.MediaSubtitleDiscoveryProvider
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 字幕中心（P2 切片一）ViewModel 链路：
 * - 发现 → 列表进入字幕中心状态；
 * - 匹配记忆自动回放（字幕+偏移）；
 * - **手动选择优先**：迟到的发现不覆盖用户已选（embedded 或外挂）；
 * - 偏移仅真实支持的内核生效；不同视频记忆互不串（键隔离在 db 层测）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerViewModelSubtitleCenterTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- fakes ----

    private class RecordingEngine(
        override val subtitleCapabilities: SubtitleCapabilities =
            SubtitleCapabilities(externalLoad = true, offsetAdjust = true),
    ) : PlaybackEnginePort {
        override val kind: EngineKind = EngineKind.MEDIA3
        private val ui = MutableStateFlow(PlaybackUiState())
        override val uiState: StateFlow<PlaybackUiState> get() = ui
        override val progress: SharedFlow<PlaybackProgress> =
            kotlinx.coroutines.flow.MutableSharedFlow()
        override val events: Flow<PlaybackEvent> = flowOf()
        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> =
            MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)
        var playedSession: PlaybackSession? = null
        val loaded = mutableListOf<ExternalSubtitle>()
        val offsets = mutableListOf<Long>()
        var loadResult = true
        var offsetResult = true
        override fun attachSurface(surface: android.view.Surface?) = Unit
        override fun play(session: PlaybackSession) { playedSession = session }
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        var selectedSubtitle: TrackSelection? = null
        override fun selectSubtitleTrack(selection: TrackSelection?) { selectedSubtitle = selection }
        override suspend fun loadExternalSubtitle(subtitle: ExternalSubtitle): Boolean {
            loaded.add(subtitle)
            return loadResult
        }
        override fun setSubtitleOffset(offsetMs: Long): Boolean {
            if (!subtitleCapabilities.offsetAdjust) return false
            offsets.add(offsetMs)
            return offsetResult
        }
        override fun stop(): PlaybackProgress? = null
        override fun release() = Unit
    }

    private class MemoryStore : SubtitleMemoryStore {
        val entries = mutableMapOf<String, SubtitleMemoryEntry>()
        override suspend fun recall(versionKey: String): SubtitleMemoryEntry? = entries[versionKey]
        override suspend fun remember(entry: SubtitleMemoryEntry) {
            entries[entry.versionKey] = entry.copy(updatedAtEpochMs = 1L)
        }
        override suspend fun forget(versionKey: String) { entries.remove(versionKey) }
    }

    private class Discovery(
        private val deferred: CompletableDeferred<List<DiscoveredSubtitle>>? = null,
    ) : MediaSubtitleDiscoveryProvider {
        val calls = mutableListOf<MediaItem>()
        override suspend fun discoverSubtitles(video: MediaItem): List<DiscoveredSubtitle> {
            calls.add(video)
            return deferred?.await() ?: DEFAULT_SUBS
        }
        companion object {
            val DEFAULT_SUBS = listOf(
                DiscoveredSubtitle(
                    id = "https://nas/movie.zh.srt", name = "movie.zh", fileName = "movie.zh.srt",
                    extension = "srt", language = "zh", uri = "https://nas/movie.zh.srt",
                ),
                DiscoveredSubtitle(
                    id = "https://nas/movie.eng.ass", name = "movie.eng", fileName = "movie.eng.ass",
                    extension = "ass", language = "en", uri = "https://nas/movie.eng.ass",
                ),
            )
        }
    }

    private companion object {
        val VERSIONED_ITEM = MediaItem(
            serverId = "srv-1", id = "m1", type = MediaType.MOVIE, title = "电影",
            sizeBytes = 700_000_000L, path = "/nas/movie.mkv",
        )
    }

    private class Registry(
        private val discovery: MediaSubtitleDiscoveryProvider?,
    ) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = SimpleProvider(server.id),
            detail = Detail(VERSIONED_ITEM),
            playback = Playback(),
            subtitleDiscovery = discovery,
        )
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class SimpleProvider(override val serverId: String) : MediaProvider {
        override val type = ServerType.EMBY
        override val displayName = "s"
        override val descriptor = ProviderDescriptor(
            id = "fake", serverType = ServerType.EMBY, displayName = "fake",
            category = ProviderCategory.MEDIA_SERVER, declaredCapabilities = emptySet(),
            authMethod = com.mediahub.provider.api.AuthMethod.NONE, status = ProviderStatus.EXPERIMENTAL,
        )
        override suspend fun testConnection() = ConnectionStatus(ok = true)
    }

    private class Detail(private val item: MediaItem) : MediaDetailProvider {
        override suspend fun getItemDetail(itemId: String): MediaDetail = MediaDetail(item = item)
    }

    private class Playback : MediaPlaybackProvider {
        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions) =
            PlaybackSource(url = "https://media/stream.mkv")
    }

    private class FakeServerStore : ServerStore {
        val server = MediaServer(
            id = "srv-1", name = "NAS", type = ServerType.WEBDAV,
            baseUrl = "http://nas", createdAtEpochMs = 0,
        )
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
        override suspend fun updateServer(server: MediaServer) = Unit
        override suspend fun setDefault(id: String) = Unit
    }

    private class EmptyProgress : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
    }

    private class FakeUserPreferences : UserPreferencesRepository {
        val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun buildVm(
        engine: RecordingEngine,
        discovery: MediaSubtitleDiscoveryProvider?,
        memory: SubtitleMemoryStore,
    ): PlayerViewModel {
        val saved = SavedStateHandle(
            mapOf(
                "serverId" to "srv-1",
                "itemId" to NavArgCodec.encode("m1"),
                "title" to "电影",
            ),
        )
        return PlayerViewModel(
            savedStateHandle = saved,
            serverStore = FakeServerStore(),
            progressStore = EmptyProgress(),
            subtitleMemoryStore = memory,
            registry = Registry(discovery),
            media3EngineFactory = PlaybackEngineCreator { engine },
            mpvEngineFactory = PlaybackEngineCreator { RecordingEngine() },
            engineHistory = InMemoryEnginePreferenceHistory(),
            userPreferencesRepository = FakeUserPreferences(),
            artworkPaletteLoader = ArtworkPaletteLoader { _, _ -> null },
            logger = noOpLogger,
        )
    }

    /** 与 Registry 返回的 detail 同一版本身份（sizeBytes 优先的版本指纹）。 */
    private val videoItem = MediaItem(
        serverId = "srv-1", id = "m1", type = MediaType.MOVIE, title = "电影",
        sizeBytes = 700_000_000L, path = "/nas/movie.mkv",
    )

    // ---- 发现 ----

    @Test
    fun `discovery results reach subtitle center state`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val discovery = Discovery()
        val vm = buildVm(engine, discovery, MemoryStore())
        try {
            runCurrent()
            runCurrent()
            assertTrue(vm.resolveState.value is ResolveState.Ready)
            val center = vm.subtitleCenter.value
            assertFalse(center.discovering)
            assertEquals(2, center.discovered.size)
            assertEquals("movie.zh", center.discovered.first().name)
            // 发现用的是 resolve 出的视频条目
            assertEquals("m1", discovery.calls.single().id)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- 记忆自动回放 ----

    @Test
    fun `remembered subtitle and offset auto applied without manual selection`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val memory = MemoryStore()
        val key = SubtitleMemoryKeys.forItem(videoItem)
        memory.entries[key] = SubtitleMemoryEntry(
            versionKey = key, serverId = "srv-1",
            subtitleId = "https://nas/movie.eng.ass", offsetMs = 1_200, updatedAtEpochMs = 0,
        )
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            assertEquals("https://nas/movie.eng.ass", vm.subtitleCenter.value.selectedExternalId)
            assertEquals(1_200L, vm.subtitleCenter.value.offsetMs)
            assertEquals("text/x-ssa", engine.loaded.single().mimeType)
            assertEquals(listOf(1_200L), engine.offsets)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `remembered subtitle missing from discovery applies offset only`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val memory = MemoryStore()
        val key = SubtitleMemoryKeys.forItem(videoItem)
        memory.entries[key] = SubtitleMemoryEntry(
            versionKey = key, serverId = "srv-1",
            subtitleId = "https://nas/gone.srt", offsetMs = 800, updatedAtEpochMs = 0,
        )
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            assertNull(vm.subtitleCenter.value.selectedExternalId)
            assertEquals(800L, vm.subtitleCenter.value.offsetMs)
            assertTrue(engine.loaded.isEmpty())
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- 手动选择优先 ----

    @Test
    fun `late discovery does not override manual embedded selection`() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<DiscoveredSubtitle>>()
        val engine = RecordingEngine()
        val memory = MemoryStore()
        val key = SubtitleMemoryKeys.forItem(videoItem)
        memory.entries[key] = SubtitleMemoryEntry(
            versionKey = key, serverId = "srv-1",
            subtitleId = "https://nas/movie.zh.srt", offsetMs = 0, updatedAtEpochMs = 0,
        )
        val vm = buildVm(engine, Discovery(gate), memory)
        try {
            runCurrent()
            // 发现被 gate 挂起期间，用户手动选择了内嵌轨
            vm.onEmbeddedSubtitleSelected(TrackSelection(2, 0))
            gate.complete(Discovery.DEFAULT_SUBS)
            runCurrent()
            runCurrent()
            // 手动优先：记忆里的外挂字幕不加载、状态不覆盖
            assertTrue(engine.loaded.isEmpty())
            assertTrue(vm.subtitleCenter.value.manualSelection)
            assertEquals(TrackSelection(2, 0), engine.selectedSubtitle)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `manual external selection writes memory for this version only`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val memory = MemoryStore()
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            val target = vm.subtitleCenter.value.discovered.first { it.extension == "srt" }
            vm.selectExternalSubtitle(target)
            runCurrent()
            runCurrent()

            assertEquals("https://nas/movie.zh.srt", vm.subtitleCenter.value.selectedExternalId)
            val saved = memory.entries[SubtitleMemoryKeys.forItem(videoItem)]
            assertEquals("https://nas/movie.zh.srt", saved!!.subtitleId)
            assertEquals("srv-1", saved.serverId)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- 偏移 ----

    @Test
    fun `offset change persists into memory for the current version`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val memory = MemoryStore()
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            vm.setSubtitleOffset(-1_500L)
            runCurrent()
            assertEquals(-1_500L, vm.subtitleCenter.value.offsetMs)
            val saved = memory.entries[SubtitleMemoryKeys.forItem(videoItem)]
            assertEquals(-1_500L, saved!!.offsetMs)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `offset on engine without support surfaces notice and stores nothing`() = runTest(dispatcher) {
        val engine = RecordingEngine(
            subtitleCapabilities = SubtitleCapabilities(externalLoad = true, offsetAdjust = false),
        )
        val memory = MemoryStore()
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            vm.setSubtitleOffset(500L)
            runCurrent()
            assertEquals("当前内核不支持字幕偏移", vm.subtitleCenter.value.notice)
            assertTrue(memory.entries.isEmpty())
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- 外挂加载失败与不支持 ----

    @Test
    fun `load failure surfaces honest notice without memory write`() = runTest(dispatcher) {
        val engine = RecordingEngine().apply { loadResult = false }
        val memory = MemoryStore()
        val vm = buildVm(engine, Discovery(), memory)
        try {
            runCurrent()
            runCurrent()
            val target = vm.subtitleCenter.value.discovered.first()
            vm.selectExternalSubtitle(target)
            runCurrent()
            runCurrent()
            assertEquals("字幕加载失败：movie.zh.srt", vm.subtitleCenter.value.notice)
            assertNull(vm.subtitleCenter.value.selectedExternalId)
            assertTrue(memory.entries.isEmpty())
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    @Test
    fun `engine without external load capability rejects import selections`() = runTest(dispatcher) {
        val engine = RecordingEngine(
            subtitleCapabilities = SubtitleCapabilities(externalLoad = false, offsetAdjust = false),
        )
        val vm = buildVm(engine, Discovery(), MemoryStore())
        try {
            runCurrent()
            runCurrent()
            val candidate = DiscoveredSubtitle(
                id = "content://x/1", name = "imported", fileName = "imported.srt",
                extension = "srt", language = null, uri = "content://x/1",
            )
            vm.selectExternalSubtitle(candidate)
            runCurrent()
            assertEquals("当前内核不支持外挂字幕", vm.subtitleCenter.value.notice)
            assertTrue(engine.loaded.isEmpty())
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- SAF 导入入口：uri 持久化为候选 ----

    @Test
    fun `imported uri becomes a candidate and unsupported extension is rejected`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val vm = buildVm(engine, Discovery(), MemoryStore())
        try {
            runCurrent()
            runCurrent()
            vm.importSubtitle("imported.zh.srt", "content://docs/1/imported.zh.srt")
            runCurrent()
            assertEquals(1, vm.subtitleCenter.value.imported.size)
            assertEquals("zh", vm.subtitleCenter.value.imported.single().language)

            vm.importSubtitle("cover.jpg", "content://docs/2/cover.jpg")
            runCurrent()
            assertEquals(1, vm.subtitleCenter.value.imported.size) // 不支持格式不入候选
            assertTrue(vm.subtitleCenter.value.notice!!.contains("jpg"))
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }

    // ---- 无发现能力的数据源：状态安静为空 ----

    @Test
    fun `provider without discovery keeps center quiet`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val vm = buildVm(engine, discovery = null, memory = MemoryStore())
        try {
            runCurrent()
            runCurrent()
            assertTrue(vm.resolveState.value is ResolveState.Ready)
            assertTrue(vm.subtitleCenter.value.discovered.isEmpty())
            assertFalse(vm.subtitleCenter.value.discovering)
        } finally {
            vm.stopAndFlush()
            runCurrent()
        }
    }
}
