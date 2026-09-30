package com.mediahub.player.mpv

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackStartupTrace
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.SeekMode
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
) : PlaybackEnginePort {
    constructor(
        context: Context,
        logger: Logger,
        scope: CoroutineScope,
        httpClientFactory: HttpClientFactory,
    ) : this(logger, scope, { createMpvBridge(httpClientFactory) }, { createMpvInstance(context) },
        SystemClock::elapsedRealtime, System::currentTimeMillis)

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
    private var released = false
    private var attachedSurface: Surface? = null
    private var activeResources: Resources? = null // nativeLock only

    private class Run(val generation: Long, val session: PlaybackSession) {
        var playJob: Job? = null // stateLock
        var progressJob: Job? = null // stateLock
        var acceptsUpdates = true // stateLock
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
            old?.acceptsUpdates = false
            old?.playJob?.cancel()
            old?.progressJob?.cancel()
            _uiState.value = PlaybackUiState(durationMs = session.source.durationMs ?: 0, mediaTitle = session.itemTitle)
            // LAZY ensures stop/release can always cancel the job, even with an immediate dispatcher.
            next.playJob = scope.launch(start = CoroutineStart.LAZY) { initialize(next) }
            old
        }
        closePublished(previous)
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
            if (name == "media-title") _uiState.update { it.copy(mediaTitle = value) }
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
        // Initialization can block in JNI. Surface state is queued separately; controls need not wait.
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
    override fun selectSubtitleTrack(selection: TrackSelection?) = Unit

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

        fun nativeDeferrer(): (() -> Unit) -> Unit {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            return { action -> dispatcher.dispatch(EmptyCoroutineContext, Runnable(action)) }
        }
    }
}
