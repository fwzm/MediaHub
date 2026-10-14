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
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
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
) : PlaybackEnginePort {
    constructor(
        context: Context,
        logger: Logger,
        scope: CoroutineScope,
        httpClientFactory: HttpClientFactory,
    ) : this(
        logger,
        scope,
        { createMpvBridge(httpClientFactory) },
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
    private val _events = Channel<PlaybackEvent>(Channel.UNLIMITED)
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()
    // mpv/libass renders subtitles directly; there are no external cues or audio-session samples.
    override val subtitleCues: StateFlow<CueGroup?> = MutableStateFlow(null)
    override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)

    private val stateLock = Any()
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
        var playJob: Job? = null // stateLock
        var progressJob: Job? = null // stateLock
        var acceptsUpdates = true // stateLock
        var subtitleToken: SubtitleCache.Session? = null // stateLock
        @Volatile var requestedAtMs = 0L
        @Volatile var resources: Resources? = null // published only after successful native initialization
    }

    private class Resources(val owner: Run, val mpv: MpvInstance, val bridge: MpvBridge) {
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
                    val bridgeUrl = b.start(src.url, buildHeaders(src))
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INSTANCE_CREATE_STARTED)
                    val m = instanceFactory().also { mpv = it }
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INSTANCE_CREATED)
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INIT_STARTED)
                    m.addObserver(observer(run))
                    m.setOptionString("vo", "gpu")
                    m.setOptionString("ao", "audiotrack")
                    m.setOptionString("hwdec", "mediacodec")
                    val container = src.container?.lowercase()
                    if (container == "mpegts" || container == "ts" || container == "m2ts") {
                        m.setOptionString("demuxer-lavf-format", "mpegts")
                    }
                    val startMs = s.startPositionMs ?: s.resumePositionMs
                    if (startMs != null && startMs > 0) m.setOptionString("start", (startMs / 1000.0).toString())
                    logger.i(LogTag.PLAYER, "mpv container=$container video=${src.videoCodec} audio=${src.audioCodec} startMs=$startMs")
                    checkCurrent()
                    m.init()
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_INIT_FINISHED)
                    val initialSurface = synchronized(stateLock) { attachedSurface }
                    initialSurface?.let { m.attachSurface(it) }
                    m.observeProperty("time-pos", MpvInstance.Format.DOUBLE)
                    m.observeProperty("duration", MpvInstance.Format.DOUBLE)
                    m.observeProperty("pause", MpvInstance.Format.FLAG)
                    m.observeProperty("speed", MpvInstance.Format.DOUBLE)
                    m.observeProperty("media-title", MpvInstance.Format.STRING)
                    checkCurrent()
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_LOADFILE)
                    m.command(arrayOf("loadfile", bridgeUrl))
                    checkCurrent()
                    val resources = Resources(run, m, b)
                    // Publication and invalidation are atomic; release cannot miss a newly published handle.
                    synchronized(stateLock) {
                        checkCurrent()
                        activeResources = resources
                        run.resources = resources
                        published = true
                    }
                    // Surface changes made while init was unpublished must not block Main or get lost.
                    val latestSurface = synchronized(stateLock) { attachedSurface }
                    if (latestSurface !== initialSurface) {
                        if (latestSurface != null) m.attachSurface(latestSurface) else m.detachSurface()
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
            if (name == "pause") _uiState.update { it.copy(isPlaying = !value) }
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
                MpvInstance.Event.FILE_LOADED -> tr?.record(PlaybackStartupTrace.Milestone.MPV_FILE_LOADED)
                MpvInstance.Event.VIDEO_RECONFIG -> {
                    tr?.record(PlaybackStartupTrace.Milestone.MPV_VIDEO_RECONFIG)
                    tr?.record(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED)
                    tr?.let { logger.i(LogTag.PLAYER, "StartupTrace ${it.summary()}") }
                }
                MpvInstance.Event.AUDIO_RECONFIG -> tr?.record(PlaybackStartupTrace.Milestone.AUDIO_INPUT_FORMAT_SEEN)
                MpvInstance.Event.END_FILE -> _events.trySend(PlaybackEvent.Ended)
            }
            logger.i(LogTag.PLAYER, "mpv $event ttff=${elapsedRealtime() - run.requestedAtMs}ms")
        }
    }

    private fun isCurrent(run: Run) = !released && current === run && generation == run.generation && run.acceptsUpdates

    private inline fun withCurrent(run: Run, action: () -> Unit) {
        synchronized(stateLock) { if (isCurrent(run)) action() }
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
        withNative { _, m ->
            val latest = synchronized(stateLock) { attachedSurface }
            if (latest != null) m.attachSurface(latest) else m.detachSurface()
        }
    }

    override fun togglePlayPause() = withNative { run, m ->
        val playing = _uiState.value.isPlaying
        m.setPropertyBoolean("pause", playing)
        withCurrent(run) { _events.trySend(if (playing) PlaybackEvent.Paused else PlaybackEvent.Resumed) }
    }

    override fun seekTo(positionMs: Long, mode: SeekMode) = withNative { run, m ->
        m.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
        if (mode == SeekMode.COMMIT) withCurrent(run) { _events.trySend(PlaybackEvent.Seeked) }
    }

    override fun setSpeed(speed: Float) = withNative { run, m ->
        val clamped = speed.coerceIn(0.1f, 5f)
        m.setPropertyDouble("speed", clamped.toDouble())
        withCurrent(run) { _uiState.update { it.copy(speed = clamped) } }
    }

    override fun selectAudioTrack(selection: TrackSelection?) = Unit
    override fun selectSubtitleTrack(selection: TrackSelection?) {
        synchronized(stateLock) { subtitleOperation++ }
        if (selection == null) withNative { _, m -> m.command(arrayOf("set", "sid", "no")) }
    }

    // ---- 外挂字幕 / 偏移（P2 字幕中心切片一） ----

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
                if (accepted) return ownsOperation()
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
            _uiState.update { it.copy(isPlaying = false) }
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

    private fun progressSnapshot(run: Run, m: MpvInstance?, fallback: PlaybackUiState): PlaybackProgress {
        val s = run.session
        val pos = m?.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() } ?: fallback.positionMs
        val dur = m?.getPropertyDouble("duration")?.let { (it * 1000).toLong() } ?: fallback.durationMs
        return PlaybackProgress(
            serverId = s.serverId, itemId = s.itemId, positionMs = pos,
            durationMs = if (dur > 0) dur else (s.source.durationMs ?: 0),
            isPaused = m?.getPropertyBoolean("pause") ?: !fallback.isPlaying, updatedAtEpochMs = currentTimeMillis(),
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
        const val SUBTITLE_CONFIRM_ATTEMPTS = 21
        const val SUBTITLE_CONFIRM_INTERVAL_MS = 50L

        fun nativeDeferrer(): (() -> Unit) -> Unit {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            return { action -> dispatcher.dispatch(EmptyCoroutineContext, Runnable(action)) }
        }
    }
}
