package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.network.PlaybackNetworkTraceRegistry
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.MediaTypeGuesser
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.SubtitleStyle
import com.mediahub.player.engine.EnginePreferenceHistory
import com.mediahub.player.engine.Media3EngineCreator
import com.mediahub.player.engine.MpvEngineCreator
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackStartupTrace
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.ProgressSyncCoordinator
import com.mediahub.player.engine.SwitchablePlaybackEngine
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 播放源解析状态。 */
sealed interface ResolveState {
    data object Resolving : ResolveState
    data object Ready : ResolveState
    data class Failed(val message: String) : ResolveState
}

/** 系统 UI 偏好（进入播放器时应用自动横屏/沉浸式，异步加载避免主线程读 DataStore）。 */
data class PlayerSystemUiPrefs(
    val autoLandscape: Boolean,
    val immersiveBars: Boolean,
)

/** 播放诊断信息（U4-E：Overlay 展示）。 */
data class PlaybackDiagnosticsState(
    val engine: String? = null,
    val endpointName: String? = null,
    val httpStatus: Int? = null,
    val mediaProtocol: String? = null,
    val mediaFirstByteMs: Long? = null,
    val totalTTFFMs: Long? = null,
    val bufferedMs: Long = 0,
)

/** 播放页组合状态（解析状态 + 引擎状态）。 */
data class PlayerCombinedState(
    val resolve: ResolveState = ResolveState.Resolving,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val mediaTitle: String? = null,
    val error: com.mediahub.core.network.PlaybackError? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val progressStore: ProgressStore,
    private val registry: MediaProviderRegistry,
    @Media3EngineCreator media3EngineFactory: PlaybackEngineCreator,
    @MpvEngineCreator mpvEngineFactory: PlaybackEngineCreator,
    engineHistory: EnginePreferenceHistory,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val logger: Logger,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    // itemId 经 NavArgCodec(Base64 URL_SAFE) 传输，兼容文件路径中的 '/'（见 core:common）
    private val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))
    private val itemTitle: String = savedStateHandle["title"] ?: ""
    // 播放启动快照（详情页直传，跳过重复 detail 拉取；type 非空 = 快照有效）
    private val launchType: String = savedStateHandle["type"] ?: ""
    private val launchRuntime: String = savedStateHandle["runtime"] ?: ""
    private val launchPoster: String = savedStateHandle["poster"] ?: ""
    private val launchContainer: String = savedStateHandle["container"] ?: ""
    /** 最新偏好缓存（modeProvider 同步读取，避免 play() 挂起读 DataStore）。 */
    private var latestPreferences: com.mediahub.model.UserPreferences = com.mediahub.model.UserPreferences()

    /**
     * 引擎绑定到 ViewModel 作用域，onCleared 时释放；请求头上下文 per-engine（ADR-018）。
     * U3-A：双内核门面（Media3 快速路径 / mpv 兜底），AUTO 模式下 Media3 失败自动降级。
     */
    private val switchableEngine = SwitchablePlaybackEngine(
        scope = viewModelScope,
        media3Factory = media3EngineFactory,
        mpvFactory = mpvEngineFactory,
        history = engineHistory,
        modeProvider = { latestPreferences.playbackEngineMode },
        logger = logger,
    )
    val engine: PlaybackEnginePort = switchableEngine

    /** 正在切换兼容播放模式（UI 提示"正在切换兼容播放模式…"）。 */
    val engineSwitching: StateFlow<Boolean> get() = switchableEngine.switching

    /** 当前内核（UI 徽标/诊断）。 */
    val engineKind: StateFlow<com.mediahub.player.engine.EngineKind> get() = switchableEngine.engineKind


    /** 用户偏好（字幕样式等，播放器 Bottom Sheet 消费；Phase 1B-2.4）。 */
    val preferences: StateFlow<com.mediahub.model.UserPreferences> =
        userPreferencesRepository.flow.stateIn(viewModelScope, SharingStarted.Eagerly, com.mediahub.model.UserPreferences())

    init {
        viewModelScope.launch { userPreferencesRepository.flow.collect { latestPreferences = it } }
    }

    /**
     * 系统 UI 偏好（自动横屏/沉浸式），初始 null（未加载），DataStore 首读完成后发出非 null。
     * 避免在 composition 主线程 runBlocking 读 DataStore（冷启动首读阻塞 UI）。
     */
    val playerSystemUiPrefs: StateFlow<PlayerSystemUiPrefs?> =
        userPreferencesRepository.flow
            .map { it.let { p -> PlayerSystemUiPrefs(p.autoLandscape, p.immersiveBars) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun updateSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) {
        viewModelScope.launch {
            userPreferencesRepository.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }
        }
    }

    private val _resolveState = MutableStateFlow<ResolveState>(ResolveState.Resolving)
    val resolveState: StateFlow<ResolveState> = _resolveState.asStateFlow()

    /** 服务器显示名 + 图标（Overlay 展示，Item 8）。 */
    private val _serverDisplayName = MutableStateFlow<String?>(null)
    val serverDisplayName: StateFlow<String?> = _serverDisplayName.asStateFlow()
    private val _serverIcon = MutableStateFlow<String?>(null)
    val serverIcon: StateFlow<String?> = _serverIcon.asStateFlow()

    /** 播放诊断信息（U4-E：Overlay 展示引擎/协议/首包/缓冲）。 */
    val diagnostics: StateFlow<PlaybackDiagnosticsState?> = combine(
        engineKind,
        engine.uiState,
        _serverDisplayName,
    ) { kind, ui, serverName ->
        val trace = currentTrace
        PlaybackDiagnosticsState(
            engine = kind.name,
            endpointName = serverName,
            httpStatus = trace?.metadata("mediaCode")?.toIntOrNull(),
            mediaProtocol = trace?.metadata("mediaProtocol"),
            mediaFirstByteMs = trace?.milestoneElapsedMs(PlaybackStartupTrace.Milestone.MEDIA_FIRST_BYTE),
            totalTTFFMs = trace?.milestoneElapsedMs(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED),
            bufferedMs = ui.durationMs.takeIf { it > 0 }?.let { dur ->
                (ui.positionMs + 30_000L).coerceAtMost(dur) - ui.positionMs
            } ?: 0L,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前 Provider 句柄（能力组合，ADR-014）；resolve 成功后可用。 */
    private var handle: ProviderHandle? = null

    /**
     * 进度同步管线（ADR-017）：本地快照 5s 采样、远端上报按 Provider 间隔、
     * 关键事件（Pause/Seek/Ended）立即同步、退出时 final flush。
     * 不再每秒写库 + 上报。
     */
    private val syncCoordinator = ProgressSyncCoordinator(
        scope = viewModelScope,
        localSave = { progressStore.save(it) },
        remoteReport = { progress ->
            handle?.progress?.let { runCatching { it.reportProgress(progress) } }
        },
        // 最终退出走 Provider 的 final 操作（如 Jellyfin /Sessions/Playing/Stopped），
        // 与普通 remote throttle 分流（ADR-039 review hardening）
        remoteFinalReport = { progress ->
            handle?.progress?.let { runCatching { it.reportFinalProgress(progress) } }
        },
    )
    private var syncStarted = false
    private var stopped = false
    private var currentTrace: PlaybackStartupTrace? = null

    val uiState: StateFlow<PlayerCombinedState> =
        combine(engine.uiState, _resolveState) { player, resolve ->
            PlayerCombinedState(
                resolve = resolve,
                isPlaying = player.isPlaying,
                isBuffering = player.isBuffering,
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                speed = player.speed,
                mediaTitle = player.mediaTitle,
                error = player.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerCombinedState())

    init {
        resolve()
    }

    /** 解析播放源并起播：server → handle → detail → resolvePlayback。 */
    fun resolve() {
        viewModelScope.launch {
            _resolveState.value = ResolveState.Resolving
            val trace = PlaybackStartupTrace(
                traceId = PlaybackStartupTrace.newTraceId(),
                serverId = serverId,
                itemId = itemId,
                requestedEngineMode = latestPreferences.playbackEngineMode.name,
            )
            currentTrace = trace
            PlaybackNetworkTraceRegistry.set(trace.asSink())
            trace.record(PlaybackStartupTrace.Milestone.PLAY_REQUESTED)
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                _serverDisplayName.value = server.displayName
                _serverIcon.value = server.icon
                val providerHandle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                handle = providerHandle

                val detailProvider = providerHandle.detail
                val playbackProvider = providerHandle.playback
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的播放能力尚未接入")

                // review P2-5：browse-only 数据源（如本地文件树）无详情能力时，
                // 按 itemId 重建条目并推断媒体类型（避免退化为 OTHER 污染"继续观看"元数据）。
                // Player Startup 优化：详情页已传快照（type 非空）时跳过 detail 拉取。
                val item = if (launchType.isNotBlank()) {
                    MediaItem(
                        serverId = serverId,
                        id = itemId,
                        type = runCatching { MediaType.valueOf(launchType) }
                            .getOrElse { MediaTypeGuesser.forPath(itemId) },
                        title = itemTitle.ifBlank { itemId.substringAfterLast('/') },
                        runtimeMs = launchRuntime.toLongOrNull(),
                        posterUrl = launchPoster.ifBlank { null },
                        container = launchContainer.ifBlank { null },
                    )
                } else {
                    detailProvider?.getItemDetail(itemId)?.item ?: MediaItem(
                        serverId = serverId,
                        id = itemId,
                        type = MediaTypeGuesser.forPath(itemId),
                        title = itemTitle.ifBlank { itemId.substringAfterLast('/') },
                        path = itemId,
                    )
                }
                trace.record(PlaybackStartupTrace.Milestone.DETAIL_SNAPSHOT_READY)
                val resume = progressStore.getResume(serverId, itemId)
                val source = playbackProvider.resolvePlayback(
                    item,
                    PlaybackOptions(startPositionMs = resume, enableDirectPlay = true),
                )
                trace.record(PlaybackStartupTrace.Milestone.SOURCE_RESOLVED)
                logger.i(LogTag.PLAYER, "StartupTrace " + trace.summary())
                engine.play(
                    PlaybackSession(
                        serverId = serverId,
                        itemId = itemId,
                        itemTitle = item.title.ifBlank { itemTitle },
                        source = source,
                        resumePositionMs = resume,
                        itemType = item.type,
                        posterUrl = item.posterUrl,
                        trace = trace,
                    )
                )

                if (!syncStarted) {
                    syncCoordinator.start(
                        progress = engine.progress,
                        events = engine.events,
                        remoteIntervalMs = providerHandle.progress?.remoteReportIntervalMs
                            ?: 10_000L,
                    )
                    syncStarted = true
                }
                _resolveState.value = ResolveState.Ready
                PlaybackNetworkTraceRegistry.set(null)
            } catch (e: Exception) {
                trace.record(PlaybackStartupTrace.Milestone.FAILED)
                trace.putMetadata("failedStage", "SOURCE_RESOLVED")
                logger.w(LogTag.PLAYER, "StartupTrace " + trace.summary())
                PlaybackNetworkTraceRegistry.set(null)
                logger.w(LogTag.PLAYER, "播放解析失败 serverId=$serverId itemId=$itemId", e)
                _resolveState.value = ResolveState.Failed(userMessage(e))
            }
        }
    }

    /**
     * 显式退出状态机（ADR-023）：保证退出时本地快照与远端上报不丢。
     *
     * 顺序：暂停读取 position（engine.stop，发出 Stopped）→ 生成最终进度 →
     * 停止协调器（禁止 final 之后的新 remote work）→ final flush
     * （远端 final 上报短超时，不阻塞退出；Jellyfin 走 /Sessions/Playing/Stopped）→
     * 释放播放器。幂等：可被返回按钮与 onDispose 兜底重复调用。
     */
    suspend fun stopAndFlush() {
        if (stopped) return
        stopped = true
        val finalProgress = engine.stop()
        // 先停 periodic/critical 管线（禁止 final 之后的新 remote work——防
        // Stopped 后被排队 sample 以 Playing/Progress 重开 Jellyfin 会话），
        // 再执行单次权威 final 上报；flushFinal 不依赖 coordinator job。
        syncCoordinator.stop()
        syncCoordinator.flushFinal(finalProgress)
        engine.release()
    }

    /** 异步兜底入口（PlayerScreen onDispose 使用；返回按钮走 [stopAndFlush] 同步流程）。 */
    fun stopAndFlushAsync() {
        viewModelScope.launch { stopAndFlush() }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "播放失败"
        else -> "播放失败：${e.message}"
    }

    override fun onCleared() {
        // 兜底：若未走 stopAndFlush（如进程销毁/异常路径），确保停止采样并释放资源。
        if (!stopped) {
            engine.stop()
            syncCoordinator.stop()
            engine.release()
        }
        super.onCleared()
    }
}
