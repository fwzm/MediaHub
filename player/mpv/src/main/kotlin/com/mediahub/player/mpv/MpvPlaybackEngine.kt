package com.mediahub.player.mpv

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.SubtitleStyle
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackEventMessage
import com.mediahub.player.engine.PlaybackEventSnapshot
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackStartupTrace
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.SubtitleCapabilities
import com.mediahub.player.engine.TrackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext

/** mpv compatibility engine. Native/bridge resources belong to exactly one playback generation. */
class MpvPlaybackEngine internal constructor(
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val bridgeFactory: () -> MpvBridge,
    private val instanceFactory: () -> MpvInstance,
    private val elapsedRealtime: () -> Long,
    private val currentTimeMillis: () -> Long,
    // Always dispatch, independent of the playback scope: queued teardown must survive its cancellation.
    private val deferNative: (() -> Unit) -> Unit = nativeDeferrer(),
    // 外挂字幕落地缓存（P2）：lazy，首个字幕请求才创建；未配置时 loadExternalSubtitle 如实失败。
    private val subtitleCacheFactory: () -> SubtitleCache = {
        error("subtitle cache not configured")
    },
    /** M2（ADR-044）：设备 HDR 证据；默认未知 → 不下发任何色调映射选项。 */
    private val hdrEvidence: DeviceHdrEvidenceProvider = DeviceHdrEvidenceProvider { null },
) : PlaybackEnginePort {
    constructor(
        context: Context,
        logger: Logger,
        scope: CoroutineScope,
        httpClientFactory: HttpClientFactory,
        hdrEvidence: DeviceHdrEvidenceProvider = DeviceHdrEvidenceProvider { null },
        /**
         * M16/G4：观测台账。**生产必须传入**（AppModule 注入单例），默认 null 仅用于既有测试。
         * 传了才会在回环接入 / 本地文件读取处上报，否则这些通道恒为未计数。
         */
        ledger: com.mediahub.core.network.NetworkExchangeLedger? = null,
    ) : this(
        logger,
        scope,
        { createMpvBridge(httpClientFactory, ledger, context.applicationContext) },
        { createMpvInstance(context) },
        SystemClock::elapsedRealtime,
        System::currentTimeMillis,
        nativeDeferrer(),
        {
            SubtitleCache(
                cacheDir = context.cacheDir,
                client = httpClientFactory.mediaClient().newBuilder()
                    .addNetworkInterceptor(OriginScopedCredentialInterceptor())
                    .build(),
                contentResolver = { uri, target ->
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    } catch (e: Exception) {
                        false
                    }
                },
                logger = logger,
            )
        },
        hdrEvidence,
    )

    // Creation and Run binding share stateLock: release cannot miss a concurrent first use.
    private var subtitleCacheInstance: SubtitleCache? = null

    private fun subtitleCache(run: Run): Pair<SubtitleCache, SubtitleCache.Session>? = synchronized(stateLock) {
        if (!isCurrent(run)) return@synchronized null
        val cache = subtitleCacheInstance ?: subtitleCacheFactory().also { subtitleCacheInstance = it }
        val token = run.subtitleToken ?: cache.beginSession().also { run.subtitleToken = it }
        cache to token
    }

    override val kind: EngineKind = EngineKind.MPV
    private val _uiState = MutableStateFlow(PlaybackUiState())
    override val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private val _progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val progress: SharedFlow<PlaybackProgress> = _progress.asSharedFlow()
    private val _events = Channel<PlaybackEventMessage>(Channel.UNLIMITED)
    override val events: Flow<PlaybackEventMessage> = _events.receiveAsFlow()
    // mpv/libass renders subtitles directly; there are no external cues or audio-session samples.
    override val subtitleCues: StateFlow<CueGroup?> = MutableStateFlow(null)
    override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)

    private val stateLock = Any()
    private var presentationOptions = MpvSubtitlePresentation.options(SubtitleStyle(), 18)
    private var presentationRevision = 0L // stateLock; a newer preference supersedes an in-flight write
    // Never acquire this lock while holding stateLock: JNI may wait for an observer callback.
    private val nativeLock = Any()
    // Unlike a monitor, this also serializes reentrant play() with Main.immediate/Unconfined.
    private val initializationMutex = Mutex()
    private var generation = 0L
    private var current: Run? = null
    private var subtitleOperation = 0L // stateLock; manual Off/another load invalidates pending confirmation
    private var released = false
    private var attachedSurface: Surface? = null
    private var activeResources: Resources? = null // nativeLock only

    private class Run(val generation: Long, val session: PlaybackSession) {
        var requestedPlayWhenReady = session.startPaused?.not() // stateLock; null keeps native startup default
        var playJob: Job? = null // stateLock
        var progressJob: Job? = null // stateLock
        var acceptsUpdates = true // stateLock
        var subtitleToken: SubtitleCache.Session? = null // stateLock
        /** V15-A: 最近一次读到的 mpv track-list 快照（stateLock 保护；切源时整体替换）。 */
        var mpvTrackList: List<Map<String, String?>> = emptyList()
        @Volatile var requestedAtMs = 0L
        @Volatile var resources: Resources? = null // published only after successful native initialization
    }

    private class Resources(val owner: Run, val mpv: MpvInstance, val bridge: MpvBridge,
        val surfaces: MpvSurfaceBinding) {
        var closed = false // nativeLock
    }

    override fun play(session: PlaybackSession) {
        val next: Run
        val previous = synchronized(stateLock) {
            if (released) return
            next = Run(++generation, session)
            val old = current
            current = next
            subtitleOffsetMsValue = 0L
            next.subtitleToken = subtitleCacheInstance?.beginSession()
            old?.acceptsUpdates = false
            old?.playJob?.cancel()
            old?.progressJob?.cancel()
            _uiState.value = PlaybackUiState(durationMs = session.source.durationMs ?: 0, mediaTitle = session.itemTitle)
            // LAZY ensures stop/release can always cancel the job, even with an immediate dispatcher.
            next.playJob = scope.launch(start = CoroutineStart.LAZY) { initialize(next) }
            old
        }
        closePublished(previous)
        previous?.let { subtitleCacheInstance?.endSession(it.subtitleToken) }
        nativeOutsideStateLock { next.playJob?.start() }
    }

    private suspend fun initialize(run: Run) {
        val job = currentCoroutineContext()
        fun checkCurrent() {
            job.ensureActive()
            synchronized(stateLock) {
                if (!isCurrent(run)) throw CancellationException("mpv playback generation invalidated")
            }
        }

        // JNI initialization is not cancellable. Keep partial resources local until every check passes;
        // stop/release during init invalidates immediately, then cleanup runs as soon as JNI returns.
        initializationMutex.withLock {
            synchronized(nativeLock) {
                checkCurrent()
                activeResources?.let { closeResources(it) }
                var bridge: MpvBridge? = null
                var mpv: MpvInstance? = null
                var published = false
                var completed = false
                try {
                    val s = run.session
                    val src = s.source
                    val tr = s.trace
                    run.requestedAtMs = elapsedRealtime()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_BRIDGE_START)
                    val b = bridgeFactory().also { bridge = it }
                    checkCurrent()
                    // M9-P0：本地媒体走本地字节源分支。判定基于**真实文件存在性**，
                    // 不依赖 "/storage" 之类前缀猜测（避免把 URL 误判/漏判）。
                    val localPath = src.url.takeIf {
                        it.startsWith("file:", ignoreCase = true) || (it.startsWith("/") && java.io.File(it).isFile)
                    }
                    val bridgeUrl = if (src.url.startsWith("content:", ignoreCase = true)) {
                        b.startContentUri(src.url)
                    } else if (localPath != null) {
                        b.startLocalFile(localPath)
                    } else {
                        b.start(src.url, buildHeaders(src))
                    }
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INSTANCE_CREATE_STARTED)
                    val m = instanceFactory().also { mpv = it }
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INSTANCE_CREATED)
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INIT_STARTED)
                    m.addObserver(observer(run))
                    m.setOptionString("vo", "gpu")
                    m.setOptionString("ao", "audiotrack")
                    // Android fontconfig fallback can select an OEM clock font whose space
                    // advance is zero. Use the platform text family; CJK still uses fallback.
                    m.setOptionString("sub-font", "Roboto")
                    val initialPresentation = synchronized(stateLock) { presentationOptions }
                    initialPresentation.forEach { (name, value) ->
                        checkCurrent()
                        m.setOptionString(name, value)
                    }
                    m.setOptionString("hwdec", "mediacodec")
                    // M6 真机缺陷修复（P1）：关闭 mpv 的 ytdl 钩子。
                    // MediaHub 的媒体**一律经回放环桥**（http://127.0.0.1:<port>/media/<random>）
                    // 以字节流形式提供，不是站点 URL；mpv 默认 ytdl=auto 会把它当站点，
                    // 启动 yt-dlp 子进程（真机日志：Subprocess failed: init / youtube-dl failed）
                    // 随后 "loading failed (reason 4)" → 黑屏、无首帧、无时钟推进。
                    m.setOptionString("ytdl", "no")
                    val container = src.container?.lowercase()
                    if (container == "mpegts" || container == "ts" || container == "m2ts") {
                        m.setOptionString("demuxer-lavf-format", "mpegts")
                    }
                    // M2（ADR-044）：只下发有二进制证据的选项；证据缺失（null）时不干预。
                    // 任何异常都不得影响起播——降级为空选项集。
                    runCatching {
                        MpvTuningOptions.build(
                            MpvTuningInput(
                                sourceHdr = src.hdrType,
                                deviceSupportsSourceHdr = hdrEvidence.supports(src.hdrType),
                            ),
                        )
                    }.getOrDefault(emptyList()).forEach { tuning ->
                        m.setOptionString(tuning.name, tuning.value)
                        logger.i(LogTag.PLAYER, "mpv tuning " + tuning.name + "=" + tuning.value)
                    }
                    val startMs = s.startPositionMs ?: s.resumePositionMs
                    if (startMs != null && startMs > 0) m.setOptionString("start", (startMs / 1000.0).toString())
                    logger.i(LogTag.PLAYER, "mpv container=$container video=${src.videoCodec} audio=${src.audioCodec} startMs=$startMs")
                    checkCurrent()
                    m.init()
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INIT_FINISHED)
                    val initialSurface = synchronized(stateLock) { attachedSurface }
                    val surfaces = MpvSurfaceBinding(m)
                    surfaces.replace(initialSurface)
                    m.observeProperty("time-pos", MpvInstance.Format.DOUBLE)
                    m.observeProperty("duration", MpvInstance.Format.DOUBLE)
                    m.observeProperty("pause", MpvInstance.Format.FLAG)
                    m.observeProperty("speed", MpvInstance.Format.DOUBLE)
                    m.observeProperty("media-title", MpvInstance.Format.STRING)
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_LOADFILE)
                    m.command(arrayOf("loadfile", bridgeUrl))
                    checkCurrent()
                    // G3：降级重播必须尊重暂停意图（默认 null = 沿用既有「起播即播放」行为）。
                    // 必须在 loadfile 之后设置，否则会被 loadfile 重置为新文件默认的播放态。
                    val resources = Resources(run, m, b, surfaces)
                    // Publication and invalidation are atomic; release cannot miss a newly published handle.
                    synchronized(stateLock) {
                        checkCurrent()
                        activeResources = resources
                        run.resources = resources
                        published = true
                    }
                    // Preferences can change while native init is unpublished. Apply the latest
                    // revision through runtime properties, not the pre-init option API.
                    applySubtitlePresentation(run, m)
                    checkCurrent()
                    // An explicit command may arrive inside loadfile before publication. Read it
                    // after publishing; later commands can now queue behind nativeLock safely.
                    val desiredPlay = synchronized(stateLock) { run.requestedPlayWhenReady }
                    if (desiredPlay != null) {
                        m.setPropertyBoolean("pause", !desiredPlay)
                        withCurrent(run) { _uiState.update {
                            it.copy(isPlaying = it.isPlaying && desiredPlay, playWhenReady = desiredPlay)
                        } }
                    }
                    // Surface changes made while init was unpublished must not block Main or get lost.
                    val latestSurface = synchronized(stateLock) { attachedSurface }
                    if (latestSurface !== initialSurface) {
                        surfaces.replace(latestSurface)
                    }
                    startProgressLoop(run)
                    withCurrent(run) { logger.i(LogTag.PLAYER, "mpv 开始播放 serverId=${s.serverId} itemId=${s.itemId}") }
                    completed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withCurrent(run) {
                        run.acceptsUpdates = false
                        logger.e(LogTag.PLAYER, "mpv 起播失败", e)
                        _uiState.update { it.copy(error = PlaybackError(PlaybackError.Code.UNKNOWN, details = mapOf("msg" to (e.message ?: "")), cause = e)) }
                    }
                } finally {
                    if (!completed) {
                        // Disable callbacks before destroy; native teardown may emit END_FILE/property events.
                        synchronized(stateLock) {
                            run.acceptsUpdates = false
                            run.progressJob?.cancel()
                        }
                        if (published) run.resources?.let { closeResources(it) } else closePartial(mpv, bridge)
                    }
                }
            }
        }
    }

    private fun observer(run: Run) = object : MpvInstance.Observer {
        override fun property(name: String, value: Boolean) = withCurrent(run) {
            // G3：pause 属性同时表达「是否暂停」，因此它既是实际状态也是用户意图。
            if (name == "pause") _uiState.update { it.copy(isPlaying = !value, playWhenReady = !value) }
        }
        override fun property(name: String, value: Double) = withCurrent(run) {
            when (name) {
                "time-pos" -> _uiState.update { it.copy(positionMs = (value * 1000).toLong()) }
                "duration" -> if (value > 0) _uiState.update { it.copy(durationMs = (value * 1000).toLong()) }
                "speed" -> _uiState.update { it.copy(speed = value.toFloat()) }
            }
        }
        override fun property(name: String, value: String) = withCurrent(run) {
            // The session owns a known item title; native media-title can be a bridge transport filename.
            if (name == "media-title" && run.session.itemTitle.isBlank()) _uiState.update { it.copy(mediaTitle = value) }
        }
        override fun event(event: MpvInstance.Event) = withCurrent(run) {
            val tr = run.session.trace
            when (event) {
                MpvInstance.Event.FILE_LOADED -> {
                tr?.record(PlaybackStartupTrace.Milestone.MPV_FILE_LOADED)
                // V15-A：文件加载完成即枚举音轨（此前从不发布 audioTracks → 面板恒为空）
                withNative(run) { _, m -> refreshTracks(run, m) }
            }
                MpvInstance.Event.VIDEO_RECONFIG -> {
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_VIDEO_RECONFIG)
                    tr?.record(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED)
                    tr?.let { logger.i(LogTag.PLAYER, "StartupTrace ${it.summary()}") }
                }
                MpvInstance.Event.AUDIO_RECONFIG -> {
                    tr?.record(PlaybackStartupTrace.Milestone.AUDIO_INPUT_FORMAT_SEEN)
                    withNative(run) { _, m -> refreshTracks(run, m) }
                }
                MpvInstance.Event.END_FILE -> {
                    // SEEK-B 真机缺陷修复：到达 EOF 时必须更新 UI 状态。
                    //
                    // 此前只发 PlaybackEvent.Ended，**从不更新 _uiState.isEnded**；
                    // 而进度循环里的 `eof-reached` 在 mpv 结束后不一定可读。
                    // 结果：画面已冻结在最后一帧，但按钮仍显示「暂停」（isPlaying/playWhenReady 仍为 true），
                    // 用户无法用播放按钮恢复 —— 真机实测（123e243f）截图 sha256 完全相同。
                    //
                    // 修复：EOF 时同时置 isEnded = true、playWhenReady = false，
                    // 让 UI 的 `showPauseIcon = playWhenReady && !isEnded`（G3 已加）真正生效。
                    withCurrent(run) {
                        val progress = progressSnapshot(run, null,
                            _uiState.value.copy(isEnded = true, playWhenReady = false))
                        _uiState.update { it.copy(isEnded = true, playWhenReady = false) }
                        _events.trySend(PlaybackEventSnapshot(PlaybackEvent.Ended, progress))
                    }
                }
            }
            logger.i(LogTag.PLAYER, "mpv $event ttff=${elapsedRealtime() - run.requestedAtMs}ms")
        }
    }

    private fun isCurrent(run: Run) = !released && current === run && generation == run.generation && run.acceptsUpdates

    private inline fun withCurrent(run: Run, action: () -> Unit) {
        synchronized(stateLock) { if (isCurrent(run)) action() }
    }

    override fun setSubtitlePresentation(style: SubtitleStyle, sizeSp: Int) {
        val options = MpvSubtitlePresentation.options(style, sizeSp)
        val run = synchronized(stateLock) {
            if (released || presentationOptions == options) return
            presentationOptions = options
            presentationRevision++
            current
        } ?: return
        // withNative does not wait for an unpublished init; publication replays the latest revision.
        withNative(run) { owner, native -> applySubtitlePresentation(owner, native) }
    }

    private fun applySubtitlePresentation(run: Run, native: MpvInstance) {
        val (revision, options) = synchronized(stateLock) { presentationRevision to presentationOptions }
        for ((name, value) in options) {
            // A native callback can synchronously stop/release or apply newer preferences.
            if (synchronized(stateLock) { !isCurrent(run) || presentationRevision != revision }) return
            native.command(arrayOf("set", name, value))
        }
    }

    private fun withNative(action: (Run, MpvInstance) -> Unit) {
        val run = synchronized(stateLock) { current } ?: return
        withNative(run, action)
    }

    private fun withNative(run: Run, action: (Run, MpvInstance) -> Unit) {
        // An expected Run is required after suspension; never borrow the newer current Run.
        if (run.resources == null) return
        nativeOutsideStateLock {
            synchronized(nativeLock) {
                val resources = run.resources ?: return@synchronized
                if (resources.closed || synchronized(stateLock) { !isCurrent(run) }) return@synchronized
                action(run, resources.mpv)
            }
        }
    }

    override fun attachSurface(surface: Surface?) {
        synchronized(stateLock) {
            if (released) return
            attachedSurface = surface
        }
        withNative { run, _ ->
            val latest = synchronized(stateLock) { attachedSurface }
            run.resources?.surfaces?.replace(latest)
        }
    }

    /**
     * 暂停/继续切换（G3 修复）。
     *
     * 旧实现读 [_uiState].isPlaying —— 那是**异步 observer 回调**写入的派生状态，
     * 在起播瞬间（pause 属性首次回传前）恒为 false，于是「第一次点击暂停」会走成
     * resume 分支（只把 pause 设成 false，什么都不改变），表现为暂停无效、
     * 位置继续推进。
     *
     * 现在直接读 **mpv 的权威属性 pause**：同步反映真实播放意图，不依赖回调时序。
     * 读失败（属性不可用）时不发控制指令或成功事件，避免用迟到的 UI 状态猜测播放意图。
     */
    override fun togglePlayPause() {
        val pending = synchronized(stateLock) { current?.takeIf { isCurrent(it) && it.resources == null } }
        if (pending != null) {
            setPlayWhenReady(!synchronized(stateLock) { pending.requestedPlayWhenReady ?: true })
            return
        }
        withNative { run, m ->
            // Native observers are asynchronous; read authoritative pause under nativeLock.
            val paused = m.getPropertyBoolean("pause") ?: return@withNative
            m.setPropertyBoolean("pause", !paused)
            val progress = criticalProgress(run, m, _uiState.value.copy(playWhenReady = paused))
            withCurrent(run) {
                run.requestedPlayWhenReady = paused
                _uiState.update { it.copy(playWhenReady = paused) }
                _events.trySend(PlaybackEventSnapshot(if (paused) PlaybackEvent.Resumed else PlaybackEvent.Paused, progress))
            }
        }
    }

    override fun setPlayWhenReady(play: Boolean) {
        val run = synchronized(stateLock) {
            val owner = current?.takeIf { isCurrent(it) } ?: return
            owner.requestedPlayWhenReady = play
            _uiState.update { it.copy(playWhenReady = play) }
            owner
        }
        withNative(run) { _, m ->
            val previous = m.getPropertyBoolean("pause")
            m.setPropertyBoolean("pause", !play)
            val progress = criticalProgress(run, m, _uiState.value.copy(playWhenReady = play))
            withCurrent(run) {
                _uiState.update { it.copy(playWhenReady = play) }
                if (previous != !play) _events.trySend(PlaybackEventSnapshot(if (play) PlaybackEvent.Resumed else PlaybackEvent.Paused, progress))
            }
        }
    }

    override fun seekTo(positionMs: Long, mode: SeekMode) = withNative { run, m ->
        m.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
        if (mode == SeekMode.COMMIT) {
            val progress = criticalProgress(run, m, _uiState.value)
                .copy(positionMs = positionMs.coerceAtLeast(0))
            withCurrent(run) { _events.trySend(PlaybackEventSnapshot(PlaybackEvent.Seeked, progress)) }
        }
    }

    override fun setSpeed(speed: Float) = withNative { run, m ->
        val clamped = speed.coerceIn(0.1f, 5f)
        m.setPropertyDouble("speed", clamped.toDouble())
        withCurrent(run) { _uiState.update { it.copy(speed = clamped) } }
    }

    /**
     * V15-A：mpv 选轨。UI 用同类型内序号（TrackSelection.groupIndex），
     * 需解析成 mpv 的 aid（track id，≠ stream index）；null → aid=no。
     * 选择无效时不下发命令，避免静默把 aid 设成错误值。
     */
    override fun selectAudioTrack(selection: TrackSelection?) {
        if (!isTrackSelectionCurrent(selection)) return
        val run = synchronized(stateLock) { current } ?: return
        val trackList = synchronized(stateLock) { run.mpvTrackList.orEmpty() }
        val aid = MpvTrackMapper.nativeAudioId(trackList, selection) ?: run {
            if (selection != null) return // 无效序号：保持现状，不伪造成功
            "no"
        }
        withCurrent(run) {
            withNative { _, m -> m.command(arrayOf("set", "aid", aid)) }
            // 立即用 native 实际值回填，避免乐观 UI（失败时保持真实状态）
            withNative(run) { _, m -> refreshTracks(run, m) }
        }
    }

    /**
     * V15-A：从 mpv 读取 track-list 并发布 audioTracks。
     *
     * 必须在 native 锁内调用；只读属性、不下发命令。
     * 空列表（无音轨 / 尚未 file-loaded）如实发布为空，不伪造。
     */
    private fun refreshTracks(run: Run, m: MpvInstance) {
        val count = m.getPropertyDouble("track-list/count")?.toInt() ?: 0
        val safeCount = if (count < 0 || count > MAX_TRACKS) 0 else count
        val rows = ArrayList<Map<String, String?>>(safeCount)
        for (i in 0 until safeCount) {
            val row = HashMap<String, String?>(MpvTrackMapper.TRACK_PROPERTIES.size)
            for (prop in MpvTrackMapper.TRACK_PROPERTIES) {
                row[prop] = m.getPropertyString("track-list/$i/$prop")
            }
            rows += row
        }
        synchronized(stateLock) {
            if (!isCurrent(run)) return
            run.mpvTrackList = rows
        }
        val nativeAid = m.getPropertyString("aid")
        val audio = MpvTrackMapper.mapAudioTracks(rows, nativeAid)
        val primarySid = m.getPropertyString("sid")
        val subtitleRows = rows.filter { it["type"] == "sub" }
        val subtitles = MpvTrackMapper.mapSubtitleTracks(rows).mapIndexed { index, track ->
            if (primarySid == null || primarySid == "auto") track else track.copy(
                isSelected = subtitleRows.getOrNull(index)?.get("id") == primarySid,
            )
        }
        val outputFormat = m.getPropertyString("audio-out-params/format")?.takeIf(String::isNotBlank)
        // This describes samples written to the audio API, not the AC3/AAC encoded input.
        val outputMime = outputFormat?.let {
            if (it.startsWith("spdif")) "audio/x-iec61937" else "audio/raw"
        }
        withCurrent(run) {
            val old = _uiState.value
            if (old.audioFormatMime != outputMime || old.subtitleTracks.size != subtitles.size || old.audioTracks.size != audio.size) {
                logger.i(LogTag.PLAYER, "mpv tracks audio=${audio.size} subtitles=${subtitles.size} output=${outputMime ?: "unknown"}")
            }
            _uiState.update { it.copy(
                audioTracks = audio,
                subtitleTracks = subtitles,
                selectedAudio = audio.firstOrNull { track -> track.isSelected }?.let { track -> TrackSelection(track.index, 0) },
                selectedSubtitle = subtitles.firstOrNull { track -> track.isSelected }?.let { track -> TrackSelection(track.index, 0) },
                audioFormatMime = outputMime,
                audioOutputStatus = if (outputFormat != null) com.mediahub.player.engine.AudioOutputStatus.ACTIVE
                    else com.mediahub.player.engine.AudioOutputStatus.UNKNOWN,
            ) }
        }
    }
    override fun selectSubtitleTrack(selection: TrackSelection?) {
        if (!isTrackSelectionCurrent(selection)) return
        val run = synchronized(stateLock) { subtitleOperation++; current } ?: return
        val rows = synchronized(stateLock) { run.mpvTrackList }
        val sid = MpvTrackMapper.nativeSubtitleId(rows, selection) ?: run {
            if (selection != null) return
            "no"
        }
        withNative(run) { _, m ->
            m.command(arrayOf("set", "sid", sid))
            refreshTracks(run, m)
        }
    }

    // ---- 外挂字幕 / 偏移（P2 字幕中心切片一） ----

    // Media3 row snapshots cannot be reinterpreted as mpv native ordinals after fallback.
    override fun isTrackSelectionCurrent(selection: TrackSelection?): Boolean = synchronized(stateLock) {
        !released && current != null && selection?.snapshotToken == null
    }

    /**
     * mpv 能力矩阵（如实自述）：
     * - 外挂字幕 = 支持（`sub-add <本地路径> select`；http/content 先落地 cache）；
     * - 偏移 = 支持（`sub-delay` 属性，秒）。
     */
    override val subtitleCapabilities: SubtitleCapabilities =
        SubtitleCapabilities(externalLoad = true, offsetAdjust = true)

    override fun setSubtitleOffset(offsetMs: Long): Boolean {
        // ±60s 合理边界，防止误触把字幕推到整片之外
        val clamped = offsetMs.coerceIn(-60_000L, 60_000L)
        val run = synchronized(stateLock) { current?.takeIf { isCurrent(it) } } ?: return false
        var accepted = false
        withNative(run) { _, m ->
            m.setPropertyDouble("sub-delay", clamped / 1000.0)
            // A4 成功状态确认：命令调用完成 ≠ 生效——回读属性对值（上游
            // native 层业务错误码不回传，见 docs B 交接；读回值才可写状态）。
            accepted = m.getPropertyDouble("sub-delay")?.let {
                kotlin.math.abs(it - clamped / 1000.0) < 0.001
            } == true
        }
        return synchronized(stateLock) {
            if (accepted && isCurrent(run)) {
                subtitleOffsetMsValue = clamped
                true
            } else false
        }
    }

    @Volatile private var subtitleOffsetMsValue = 0L
    override val subtitleOffsetMs: Long get() = subtitleOffsetMsValue

    override suspend fun loadExternalSubtitle(subtitle: ExternalSubtitle): Boolean {
        val run: Run
        val operation: Long
        synchronized(stateLock) {
            run = current?.takeIf { isCurrent(it) } ?: return false
            operation = ++subtitleOperation
        }
        fun ownsOperation() = synchronized(stateLock) { isCurrent(run) && subtitleOperation == operation }
        val (cache, token) = subtitleCache(run) ?: return false
        val session = run.session
        val localPath = cache.localPathFor(
            uri = subtitle.uri,
            mediaUrl = session.source.url,
            scopeKey = "${session.serverId}/${session.itemId}/${session.source.url}",
            sessionHeaders = buildHeaders(session.source),
            token = token,
        ) ?: return false
        val requestContext = currentCoroutineContext()
        requestContext.ensureActive()
        var submitted = false
        var accepted = false
        try {
            withNative(run) { _, m ->
                if (!ownsOperation()) return@withNative
                accepted = primarySubtitleMatches(m, localPath)
                requestContext.ensureActive()
                if (!ownsOperation()) return@withNative
                if (!accepted) m.command(arrayOf("sub-add", localPath, "select"))
                submitted = true
            }
            if (!submitted) return false
            // libmpv command/track changes can be asynchronous. Wait at most one second,
            // releasing nativeLock between reads and validating the original Run each time.
            repeat(SUBTITLE_CONFIRM_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                if (!ownsOperation()) return false
                withNative(run) { _, m -> if (ownsOperation()) accepted = primarySubtitleMatches(m, localPath) }
                if (accepted) {
                    withNative(run) { _, m -> if (ownsOperation()) refreshTracks(run, m) }
                    return ownsOperation()
                }
                if (attempt < SUBTITLE_CONFIRM_ATTEMPTS - 1) delay(SUBTITLE_CONFIRM_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(LogTag.PLAYER, "mpv primary external subtitle confirmation failed", e)
        }
        return false
    }

    /** mpv 0.41: selected means decoded, including secondary-sid; sid identifies the primary. */
    private fun primarySubtitleMatches(m: MpvInstance, path: String): Boolean {
        val sid = m.getPropertyString("sid")?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        val rawCount = m.getPropertyDouble("track-list/count") ?: return false
        if (!rawCount.isFinite() || rawCount < 0 || rawCount > 4096 || rawCount != rawCount.toInt().toDouble()) return false
        for (i in 0 until rawCount.toInt()) {
            if (m.getPropertyString("track-list/$i/type") != "sub") continue
            if (m.getPropertyString("track-list/$i/external-filename") != path) continue
            if (m.getPropertyString("track-list/$i/id")?.toLongOrNull() != sid) continue
            if (m.getPropertyBoolean("track-list/$i/selected") != true) continue
            // Re-read primary selection after scanning a table that may change between reads.
            return m.getPropertyString("sid")?.toLongOrNull() == sid
        }
        return false
    }

    override fun stop(): PlaybackProgress? {
        val snapshot: PlaybackUiState
        val run = synchronized(stateLock) {
            val old = invalidateCurrent() ?: return null
            snapshot = _uiState.value
            _uiState.update { it.copy(isPlaying = false, playWhenReady = false) }
            _events.trySend(PlaybackEvent.Stopped)
            old
        }
        // An initializing run has no published native handle. Do not wait for a blocking JNI init.
        val resources = run.resources
        val final = if (resources == null || Thread.holdsLock(stateLock)) {
            progressSnapshot(run, null, snapshot)
        } else synchronized(nativeLock) {
            runCatching { progressSnapshot(run, resources.mpv.takeUnless { resources.closed }, snapshot) }
                .getOrElse { progressSnapshot(run, null, snapshot) }
        }
        closePublished(run)
        subtitleCacheInstance?.endSession(run.subtitleToken)
        return final
    }

    override fun release() {
        val run = synchronized(stateLock) {
            if (released) return
            released = true
            attachedSurface = null
            invalidateCurrent()
        }
        closePublished(run)
        // Do not create a cache solely to release it; cleanup uses the invalidated Run token.
        subtitleCacheInstance?.endSession(run?.subtitleToken)
    }

    /** stateLock held; invalidation never waits for JNI initialization or observer completion. */
    private fun invalidateCurrent(): Run? {
        generation++
        val old = current
        current = null
        old?.acceptsUpdates = false
        old?.playJob?.cancel()
        old?.progressJob?.cancel()
        return old
    }

    private fun closePublished(run: Run?) {
        val resources = run?.resources ?: return
        nativeOutsideStateLock { synchronized(nativeLock) { closeResources(resources) } }
    }

    /**
     * StateFlow/channel consumers can reenter synchronously on Main.immediate/Unconfined. Deferring
     * only their native work preserves atomic generation changes without reversing our lock order.
     */
    private fun nativeOutsideStateLock(action: () -> Unit) {
        if (Thread.holdsLock(stateLock)) deferNative(action) else action()
    }

    private fun closeResources(resources: Resources) {
        if (resources.closed) return
        resources.closed = true
        if (activeResources === resources) activeResources = null
        closePartial(resources.mpv, resources.bridge)
    }

    private fun closePartial(mpv: MpvInstance?, bridge: MpvBridge?) {
        runCatching { mpv?.destroy() }.onFailure { logger.w(LogTag.PLAYER, "mpv destroy failed", it) }
        runCatching { bridge?.stop() }.onFailure { logger.w(LogTag.PLAYER, "mpv bridge stop failed", it) }
    }

    private fun startProgressLoop(run: Run) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                synchronized(nativeLock) {
                    val resources = run.resources ?: return@launch
                    if (resources.closed || synchronized(stateLock) { !isCurrent(run) }) return@launch
                    val value = progressSnapshot(run, resources.mpv, _uiState.value)
                    val ended = resources.mpv.getPropertyBoolean("eof-reached") ?: false
                    // FILE_LOADED may precede publication; track changes and delayed audio output
                    // must still become visible after that event and after sid/aid reconfiguration.
                    refreshTracks(run, resources.mpv)
                    withCurrent(run) {
                        _uiState.update { it.copy(positionMs = value.positionMs, durationMs = value.durationMs, isEnded = ended) }
                        _progress.tryEmit(value)
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
        synchronized(stateLock) {
            if (isCurrent(run)) run.progressJob = job else job.cancel()
        }
        job.start()
    }

    // Called under nativeLock, before acquiring stateLock. A failed diagnostic read
    // must not turn a successfully issued control into an exception.
    private fun criticalProgress(run: Run, m: MpvInstance, fallback: PlaybackUiState): PlaybackProgress =
        runCatching { progressSnapshot(run, m, fallback) }
            .getOrElse { progressSnapshot(run, null, fallback) }

    private fun progressSnapshot(run: Run, m: MpvInstance?, fallback: PlaybackUiState): PlaybackProgress {
        val s = run.session
        val pos = m?.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() } ?: fallback.positionMs
        val dur = m?.getPropertyDouble("duration")?.let { (it * 1000).toLong() } ?: fallback.durationMs
        return PlaybackProgress(
            serverId = s.serverId, itemId = s.itemId, positionMs = pos,
            durationMs = if (dur > 0) dur else (s.source.durationMs ?: 0),
            isPaused = m?.getPropertyBoolean("pause") ?: !fallback.playWhenReady, updatedAtEpochMs = currentTimeMillis(),
            sessionId = s.source.sessionId,
            mode = s.source.mode, itemTitle = s.itemTitle, itemType = s.itemType, posterUrl = s.posterUrl,
        )
    }

    private fun buildHeaders(source: PlaybackSource): Map<String, String> {
        val headers = source.headers.toMutableMap()
        source.cookies.takeIf { it.isNotEmpty() }?.let { cookies ->
            headers["Cookie"] = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }
        return headers
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L

        /** V15-A：防御异常 track-list/count（与字幕枚举 4096 上限一致）。 */
        const val MAX_TRACKS = 4096
        const val SUBTITLE_CONFIRM_ATTEMPTS = 21
        const val SUBTITLE_CONFIRM_INTERVAL_MS = 50L

        fun nativeDeferrer(): (() -> Unit) -> Unit {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            return { action -> dispatcher.dispatch(EmptyCoroutineContext, Runnable(action)) }
        }
    }
}
