package com.mediahub.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.SubtitleMemoryEntry
import com.mediahub.core.database.repository.SubtitleMemoryKeys
import com.mediahub.core.database.repository.SubtitleMemoryStore
import com.mediahub.core.network.PlaybackNetworkTraceRegistry
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.MediaTypeGuesser
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.SubtitleFormats
import com.mediahub.player.engine.EnginePreferenceHistory
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.Media3EngineCreator
import com.mediahub.player.engine.MpvEngineCreator
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackStartupTrace
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.ProgressSyncCoordinator
import com.mediahub.player.engine.SubtitleCapabilities
import com.mediahub.player.engine.SwitchablePlaybackEngine
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.DiscoveredSubtitle
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
import kotlinx.coroutines.flow.update
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

/**
 * 字幕中心状态（P2 切片一：同目录发现 + 导入 + 外挂加载 + 偏移 + 匹配记忆）。
 * [manualSelection] = 用户本会话手动选过字幕；此后迟到发现与记忆回放都不得覆盖。
 */
data class SubtitleCenterState(
    val discovering: Boolean = false,
    val discovered: List<DiscoveredSubtitle> = emptyList(),
    /** SAF 导入的候选（本会话；uri 已 persist）。 */
    val imported: List<DiscoveredSubtitle> = emptyList(),
    val selectedExternalId: String? = null,
    val offsetMs: Long = 0,
    /** 如实提示（加载失败/不支持的能力），展示后由 UI 消费。 */
    val notice: String? = null,
    val manualSelection: Boolean = false,
) {
    val allExternal: List<DiscoveredSubtitle> get() = imported + discovered
}

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
    private val subtitleMemoryStore: SubtitleMemoryStore,
    private val registry: MediaProviderRegistry,
    @Media3EngineCreator media3EngineFactory: PlaybackEngineCreator,
    @MpvEngineCreator mpvEngineFactory: PlaybackEngineCreator,
    engineHistory: EnginePreferenceHistory,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val artworkPaletteLoader: ArtworkPaletteLoader,
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

    /** 启动快照携带的容器（详情页直传；可能为空串=未提供）。 */
    val launchContainerOrNull: String?
        get() = launchContainer.takeIf { it.isNotBlank() }
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


    /**
     * 用户偏好（字幕样式等，播放器 Bottom Sheet 消费；Phase 1B-2.4）。
     *
     * null 是刻意保留的“DataStore 首帧尚未到达”状态。视觉层在此期间必须保持
     * 0 fps，不能先用默认 Aurora 启动一次再被持久化的 Off 覆盖。
     */
    private val _preferences = MutableStateFlow<com.mediahub.model.UserPreferences?>(null)
    val preferences: StateFlow<com.mediahub.model.UserPreferences?> = _preferences.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.flow.collect { stored ->
                latestPreferences = stored
                _preferences.value = stored
            }
        }
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

    fun updatePlayerVisualEffects(
        transform: (PlayerVisualEffectsPreferences) -> PlayerVisualEffectsPreferences,
    ) {
        viewModelScope.launch { userPreferencesRepository.updatePlayerVisualEffects(transform) }
    }

    fun resetPlayerVisualEffects() {
        viewModelScope.launch { userPreferencesRepository.resetPlayerVisualEffects() }
    }

    // ---- 字幕中心（P2 切片一：发现/导入/外挂加载/偏移/匹配记忆） ----

    private val _subtitleCenter = MutableStateFlow(SubtitleCenterState())
    val subtitleCenter: StateFlow<SubtitleCenterState> = _subtitleCenter.asStateFlow()

    /** 当前视频版本指纹（匹配记忆键）；resolve 成功后可用。 */
    private var versionKey: String? = null
    private var subtitleCenterJob: kotlinx.coroutines.Job? = null

    /**
     * resolve 成功后启动：同目录发现 →（未手动选择时）回放匹配记忆。
     * 记忆键 = serverId+itemId+sizeBytes（或路径指纹）——不同视频/版本互不共享。
     */
    private fun startSubtitleCenter(item: MediaItem) {
        subtitleCenterJob?.cancel()
        _subtitleCenter.value = SubtitleCenterState()
        val key = SubtitleMemoryKeys.forItem(item)
        versionKey = key
        subtitleCenterJob = viewModelScope.launch {
            val discovery = handle?.subtitleDiscovery
            if (discovery == null) {
                _subtitleCenter.update { it.copy(discovering = false) }
                return@launch
            }
            _subtitleCenter.update { it.copy(discovering = true) }
            val found = runCatching { discovery.discoverSubtitles(item) }
                .onFailure { logger.w(LogTag.PLAYER, "字幕发现失败 itemId=${item.id}", it) }
                .getOrDefault(emptyList())
            _subtitleCenter.update { it.copy(discovering = false, discovered = found) }
            // 迟到发现保护：等待期间用户已手动选择则不覆盖（手动优先）。
            if (_subtitleCenter.value.manualSelection) return@launch
            val memory = subtitleMemoryStore.recall(key) ?: return@launch
            if (memory.offsetMs != 0L && engine.setSubtitleOffset(memory.offsetMs)) {
                _subtitleCenter.update { it.copy(offsetMs = memory.offsetMs) }
            }
            val target = memory.subtitleId
                ?.let { id -> found.find { it.id == id } }
                ?: return@launch
            applyExternalSubtitle(target, remember = false)
        }
    }

    /** 外挂字幕候选点击：真实加载到当前内核；成功才记忆（手动选择标记 + 匹配记忆写入）。 */
    fun selectExternalSubtitle(subtitle: DiscoveredSubtitle) {
        viewModelScope.launch {
            val ok = applyExternalSubtitle(subtitle, remember = true)
            if (ok) {
                _subtitleCenter.update { it.copy(manualSelection = true) }
            }
        }
    }

    /**
     * 专业/精简切换（设置页或播放面板内快速切换）：只改信息面板密度，
     * 不重建播放、不切换内核、不改画质。
     */
    fun updateProfessionalInfo(
        transform: (ProfessionalInfoPreferences) -> ProfessionalInfoPreferences,
    ) {
        viewModelScope.launch {
            userPreferencesRepository.update {
                it.copy(professionalInfo = transform(it.professionalInfo))
            }
        }
    }

    /** 内嵌字幕轨手动选择：标记本会话手动优先，迟到的发现/记忆不再覆盖。 */
    fun onEmbeddedSubtitleSelected(selection: TrackSelection?) {
        engine.selectSubtitleTrack(selection)
        _subtitleCenter.update { it.copy(manualSelection = true) }
    }

    /**
     * SAF 导入回传：入口 + 持久化收到的 uri（真实 ACTION_OPEN_DOCUMENT）。
     * 扩展名不支持时如实提示，不伪造候选。
     */
    fun importSubtitle(displayName: String, uri: String) {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in SubtitleFormats.EXTENSIONS) {
            _subtitleCenter.update {
                it.copy(notice = "不支持的字幕格式：${displayName.substringAfterLast('.', "?")}")
            }
            return
        }
        val candidate = DiscoveredSubtitle(
            id = uri,
            name = displayName.substringBeforeLast('.').ifBlank { displayName },
            fileName = displayName,
            extension = extension,
            language = SubtitleFormats.languageFromFileName(displayName),
            uri = uri,
        )
        _subtitleCenter.update {
            if (it.allExternal.any { c -> c.id == candidate.id }) it
            else it.copy(imported = it.imported + candidate)
        }
    }

    fun consumeSubtitleNotice() {
        _subtitleCenter.update { it.copy(notice = null) }
    }

    /** 偏移调整（仅 mpv 内核真实生效；Media3 无公开偏移 API，引擎层如实拒绝）。 */
    fun setSubtitleOffset(offsetMs: Long) {
        if (!engine.setSubtitleOffset(offsetMs)) {
            _subtitleCenter.update { it.copy(notice = "当前内核不支持字幕偏移") }
            return
        }
        _subtitleCenter.update { it.copy(offsetMs = offsetMs) }
        persistMemory()
    }

    private suspend fun applyExternalSubtitle(
        subtitle: DiscoveredSubtitle,
        remember: Boolean,
    ): Boolean {
        if (!engine.subtitleCapabilities.externalLoad) {
            _subtitleCenter.update { it.copy(notice = "当前内核不支持外挂字幕") }
            return false
        }
        val mimeType = subtitle.mimeType
            ?: run {
                _subtitleCenter.update { it.copy(notice = "不支持的字幕格式：.${subtitle.extension}") }
                return false
            }
        val accepted = engine.loadExternalSubtitle(
            ExternalSubtitle(
                id = subtitle.id,
                name = subtitle.name,
                uri = subtitle.uri,
                mimeType = mimeType,
                language = subtitle.language,
            ),
        )
        if (!accepted) {
            _subtitleCenter.update { it.copy(notice = "字幕加载失败：${subtitle.fileName}") }
            return false
        }
        _subtitleCenter.update { it.copy(selectedExternalId = subtitle.id) }
        if (remember) persistMemory(subtitle.id)
        return true
    }

    /** 写入匹配记忆：字幕标识 + 偏移。无任何可记内容时删除记忆。 */
    private fun persistMemory(subtitleId: String? = _subtitleCenter.value.selectedExternalId) {
        val key = versionKey ?: return
        val offset = _subtitleCenter.value.offsetMs
        if (subtitleId == null && offset == 0L) {
            viewModelScope.launch { subtitleMemoryStore.forget(key) }
            return
        }
        viewModelScope.launch {
            subtitleMemoryStore.remember(
                SubtitleMemoryEntry(
                    versionKey = key,
                    serverId = serverId,
                    subtitleId = subtitleId,
                    offsetMs = offset,
                    updatedAtEpochMs = 0, // 仓库侧补齐时间戳
                ),
            )
        }
    }

    private val _resolveState = MutableStateFlow<ResolveState>(ResolveState.Resolving)
    val resolveState: StateFlow<ResolveState> = _resolveState.asStateFlow()

    /**
     * 本次 resolve 到的播放源（源参数面板数据源，PlaybackInfo 语义）。
     * null=尚未解析成功；重试解析时先清空，面板相应字段回落为"未知"。
     */
    private val _playbackSource = MutableStateFlow<PlaybackSource?>(null)
    val playbackSource: StateFlow<PlaybackSource?> = _playbackSource.asStateFlow()

    /** 服务器显示名 + 图标（Overlay 展示，Item 8）。 */
    private val _serverDisplayName = MutableStateFlow<String?>(null)
    val serverDisplayName: StateFlow<String?> = _serverDisplayName.asStateFlow()
    private val _serverIcon = MutableStateFlow<String?>(null)
    val serverIcon: StateFlow<String?> = _serverIcon.asStateFlow()

    private val _artworkPalette = MutableStateFlow<VisualPalette?>(null)
    val artworkPalette: StateFlow<VisualPalette?> = _artworkPalette.asStateFlow()
    private var artworkUrl: String? = null

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
            _playbackSource.value = null
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
                updateArtworkPalette(item.posterUrl ?: item.backdropUrl)
                val resume = progressStore.getResume(serverId, itemId)
                val source = playbackProvider.resolvePlayback(
                    item,
                    PlaybackOptions(startPositionMs = resume, enableDirectPlay = true),
                )
                trace.record(PlaybackStartupTrace.Milestone.SOURCE_RESOLVED)
                _playbackSource.value = source
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
                // 字幕中心：同目录发现 + 匹配记忆回放（异步，不阻塞 Ready）。
                startSubtitleCenter(item)
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

    private fun updateArtworkPalette(url: String?) {
        artworkUrl = url
        if (url.isNullOrBlank()) {
            _artworkPalette.value = null
            return
        }
        val expectedUrl = url
        viewModelScope.launch {
            val palette = artworkPaletteLoader.load(
                artworkKey = "$serverId:$itemId:$expectedUrl",
                url = expectedUrl,
            )
            // Keep the previous resolved palette while a replacement loads or fails. This avoids
            // a preset -> artwork -> preset flash during retries and rejects late results.
            if (artworkUrl == expectedUrl && palette != null) _artworkPalette.value = palette
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
