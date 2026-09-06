package com.mediahub.player.engine

import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 双内核引擎门面（U3-A）：对外是单个 [PlaybackEnginePort]，内部按
 * [PlaybackEngineSelector] 选 Media3 / mpv，并在 AUTO 模式下对 Media3 的
 * 失败（Source/解码器错误、有轨无声）自动降级 mpv——保存当前位置后同位置重播。
 *
 * UI / ViewModel / ProgressSyncCoordinator 只订阅本门面的流，引擎切换对它们透明：
 * - uiState / progress / events / subtitleCues / downloadSpeedBps / audioBands 均由当前内部引擎转发；
 * - 引擎切换后转发协程重启，订阅方无需重订。
 *
 * 降级触发条件（仅 AUTO + 当前 Media3 + 本会话未降级过）：
 * - 播放错误属于 mpv 可能救回的类型（解码器 / 不支持编码 / 解析未知错误）；
 *   网络类（超时/DNS/TLS/HTTP 4xx/DRM）不降级——mpv 用同一网络栈救不回。
 * - 有音轨但持续无音频输出信号（audioFormatMime 一直为 null，经宽限期确认）。
 *
 * 降级时把签名写入 [EnginePreferenceHistory]，后续同签名直接选 mpv。
 */
class SwitchablePlaybackEngine(
    private val scope: CoroutineScope,
    private val media3Factory: PlaybackEngineCreator,
    private val mpvFactory: PlaybackEngineCreator,
    private val history: EnginePreferenceHistory,
    private val modeProvider: () -> PlaybackEngineMode,
    private val logger: Logger,
    /** 无声判据宽限期（ms）：音轨就绪后等待 audioFormatMime 出现的时间。 */
    private val audioSilentGraceMs: Long = 2_000L,
) : PlaybackEnginePort {

    private val _engineKind = MutableStateFlow(EngineKind.MEDIA3)
    /** 当前内部引擎种类（UI 可展示）。 */
    val engineKind: StateFlow<EngineKind> = _engineKind.asStateFlow()

    private val _switching = MutableStateFlow(false)
    /** 正在切换兼容播放模式（UI 顶部提示）。 */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private val _uiState = MutableStateFlow(PlaybackUiState())
    override val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _progress = MutableSharedFlow<PlaybackProgress>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val progress: SharedFlow<PlaybackProgress> = _progress.asSharedFlow()

    private val _events = Channel<PlaybackEvent>(Channel.UNLIMITED)
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private val _subtitleCues = MutableStateFlow<CueGroup?>(null)
    override val subtitleCues: StateFlow<CueGroup?> = _subtitleCues.asStateFlow()

    private val _downloadSpeedBps = MutableStateFlow(0L)
    override val downloadSpeedBps: StateFlow<Long> = _downloadSpeedBps.asStateFlow()

    private val _audioBands = MutableStateFlow<AudioBandLevels?>(null)
    override val audioBands: StateFlow<AudioBandLevels?> = _audioBands.asStateFlow()

    private val stateLock = Any()
    private var current: PlaybackEnginePort? = null
    private var forwardJob: Job? = null
    private var audioForwardJob: Job? = null
    private var media3WatchJob: Job? = null
    private var fallbackJob: Job? = null
    private var session: PlaybackSession? = null
    private var sessionGeneration = 0L
    private var attachedSurface: Surface? = null
    private var fellBackThisSession = false
    private var released = false
    /** UI 的采样需求意图；引擎切换后必须原样重施给新引擎。 */
    private var audioSpectrumEnabled = false

    override val kind: EngineKind
        get() = current?.kind ?: EngineKind.MEDIA3

    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        current?.attachSurface(surface)
    }

    override fun play(session: PlaybackSession) {
        val generation = synchronized(stateLock) {
            if (released) return
            // A pending fallback belongs to the previous session. Cancel it and invalidate its
            // generation before selecting/starting the new engine.
            fallbackJob?.cancel()
            fallbackJob = null
            sessionGeneration += 1
            this.session = session
            fellBackThisSession = false
            _switching.value = false
            sessionGeneration
        }
        session.trace?.record(PlaybackStartupTrace.Milestone.ENGINE_SELECTION_STARTED)
        val mode = modeProvider()
        val selection = PlaybackEngineSelector.select(
            source = session.source,
            mode = mode,
            mpvPreferredSignatures = history.mpvPreferredSignatures(),
        )
        logger.i(LogTag.PLAYER, "引擎选择 kind=${selection.kind} mode=$mode reason=${selection.reason}")
        session.trace?.let { t ->
            t.record(PlaybackStartupTrace.Milestone.ENGINE_SELECTED)
            t.putMetadata("engine", selection.kind.name)
            t.putMetadata("signature", CompatibilitySignature.from(session.source).key)
            t.putMetadata("selectorReason", selection.reason)
        }
        startEngine(selection.kind, session, generation)
    }

    override fun togglePlayPause() {
        current?.togglePlayPause()
    }

    override fun seekTo(positionMs: Long, mode: SeekMode) {
        current?.seekTo(positionMs, mode)
    }

    override fun setSpeed(speed: Float) {
        current?.setSpeed(speed)
    }

    override fun selectAudioTrack(selection: TrackSelection?) {
        current?.selectAudioTrack(selection)
    }

    override fun selectSubtitleTrack(selection: TrackSelection?) {
        current?.selectSubtitleTrack(selection)
    }

    override fun setAudioSpectrumEnabled(enabled: Boolean) {
        if (released) return
        audioSpectrumEnabled = enabled
        val engine = current
        if (!enabled) {
            audioForwardJob?.cancel()
            audioForwardJob = null
            _audioBands.value = null
            engine?.setAudioSpectrumEnabled(false)
            return
        }
        engine?.let {
            it.setAudioSpectrumEnabled(true)
            startAudioBandForwarding(it)
        }
    }

    override fun retryAudioSpectrumCapture() {
        if (audioSpectrumEnabled) current?.retryAudioSpectrumCapture()
    }

    override fun stop(): PlaybackProgress? {
        val engine = synchronized(stateLock) {
            invalidatePendingFallbackLocked()
            media3WatchJob?.cancel()
            media3WatchJob = null
            session = null
            current
        }
        val finalProgress = engine?.stop()
        synchronized(stateLock) {
            audioForwardJob?.cancel()
            audioForwardJob = null
        }
        _audioBands.value = null
        return finalProgress
    }

    override fun release() {
        val engine = synchronized(stateLock) {
            if (released) return
            released = true
            invalidatePendingFallbackLocked()
            forwardJob?.cancel()
            audioForwardJob?.cancel()
            media3WatchJob?.cancel()
            session = null
            val old = current
            current = null
            old
        }
        engine?.release()
        _audioBands.value = null
    }

    // ---- 内部 ----

    private fun startEngine(
        kind: EngineKind,
        session: PlaybackSession,
        expectedGeneration: Long,
        expectedSession: PlaybackSession = session,
    ) {
        synchronized(stateLock) {
            if (!isCurrentSessionLocked(expectedSession, expectedGeneration)) return

            // 新媒体会话必须释放旧引擎（连同其 Visualizer/audio session），再建立新转发链。
            current?.let { previous ->
                forwardJob?.cancel()
                audioForwardJob?.cancel()
                media3WatchJob?.cancel()
                current = null
                previous.stop()
                previous.release()
            }
            val engine = (if (kind == EngineKind.MPV) mpvFactory else media3Factory).create(scope)
            current = engine
            _engineKind.value = kind
            // 切引擎后清空旧错误/轨道状态，避免 Media3 的错误残留显示在 mpv 上
            _uiState.value = PlaybackUiState(durationMs = session.source.durationMs ?: 0, mediaTitle = session.itemTitle)
            _audioBands.value = null
            forwardJob?.cancel()
            forwardJob = scope.launch {
                launch { engine.uiState.collect { _uiState.value = it } }
                launch { engine.progress.collect { _progress.tryEmit(it) } }
                launch { engine.events.collect { _events.trySend(it) } }
                launch { engine.subtitleCues.collect { _subtitleCues.value = it } }
                launch { engine.downloadSpeedBps.collect { _downloadSpeedBps.value = it } }
            }
            audioForwardJob?.cancel()
            audioForwardJob = null
            engine.setAudioSpectrumEnabled(audioSpectrumEnabled)
            if (audioSpectrumEnabled) startAudioBandForwarding(engine)
            if (kind == EngineKind.MEDIA3) {
                media3WatchJob = scope.launch { watchMedia3Failure(engine) }
            } else {
                media3WatchJob = null
            }
            attachedSurface?.let { engine.attachSurface(it) }
            engine.play(session)
        }
    }

    private fun startAudioBandForwarding(engine: PlaybackEnginePort) {
        audioForwardJob?.cancel()
        _audioBands.value = null
        audioForwardJob = scope.launch {
            engine.audioBands.collect { levels ->
                if (current === engine && audioSpectrumEnabled && !released) {
                    _audioBands.value = levels
                }
            }
        }
    }

    /** 仅监听"由本 engine 实例引发的"失败；引擎切换后旧实例的流不再触发降级。 */
    private suspend fun watchMedia3Failure(engine: PlaybackEnginePort) = coroutineScope {
        // 错误与无声监听同属一个可取消 job；session 切换、stop、release 时一起退出。
        launch {
            engine.uiState.collect { state ->
                if (current !== engine || released || fellBackThisSession) return@collect
                if (modeProvider() != PlaybackEngineMode.AUTO) return@collect
                val error = state.error ?: return@collect
                if (error.code in FALLBACK_ERROR_CODES) {
                    fallbackToMpv(engine, "播放错误 ${error.code.name}")
                }
            }
        }
        launch {
            val audioSilent = engine.uiState.map {
                it.audioTracks.isNotEmpty() && it.audioFormatMime == null && it.isPlaying && !it.isBuffering
            }
            audioSilent.collectLatest { silent ->
                if (!silent || released || fellBackThisSession || current !== engine) return@collectLatest
                if (modeProvider() != PlaybackEngineMode.AUTO) return@collectLatest
                delay(audioSilentGraceMs)
                if (released || fellBackThisSession || current !== engine) return@collectLatest
                val state = engine.uiState.value
                val stillSilent = state.audioTracks.isNotEmpty() &&
                    state.audioFormatMime == null && state.isPlaying && !state.isBuffering
                if (stillSilent) {
                    fallbackToMpv(
                        engine,
                        "有音轨但无音频输出（${state.audioTracks.firstOrNull()?.codec ?: "?"}）",
                    )
                }
            }
        }
    }

    private fun fallbackToMpv(failedEngine: PlaybackEnginePort, reason: String) {
        val fallbackGeneration: Long
        val s: PlaybackSession
        synchronized(stateLock) {
            if (fellBackThisSession || released || current !== failedEngine) return
            s = session ?: return
            fallbackGeneration = sessionGeneration
            fellBackThisSession = true
            fallbackJob?.cancel()
        }
        _switching.value = true
        val launched = scope.launch {
            try {
                if (!isCurrentSession(s, fallbackGeneration, failedEngine)) return@launch
                history.recordMedia3Failure(CompatibilitySignature.from(s.source).key)
                if (!isCurrentSession(s, fallbackGeneration, failedEngine)) return@launch
                // 保存当前位置（先取 stop() 返回值，兜底 uiState）
                val position = failedEngine.stop()?.positionMs
                    ?: _uiState.value.positionMs
                if (!isCurrentSession(s, fallbackGeneration, failedEngine)) {
                    failedEngine.release()
                    return@launch
                }
                synchronized(stateLock) {
                    if (!isCurrentSessionLocked(s, fallbackGeneration, failedEngine)) {
                        failedEngine.release()
                        return@launch
                    }
                    forwardJob?.cancel()
                    audioForwardJob?.cancel()
                    media3WatchJob?.cancel()
                    current = null
                }
                _audioBands.value = null
                failedEngine.release()
                logger.i(
                    LogTag.PLAYER,
                    "自动降级 mpv reason=$reason resumeMs=$position signature=${CompatibilitySignature.from(s.source).key}",
                )
                startEngine(
                    EngineKind.MPV,
                    s.copy(startPositionMs = position.takeIf { it > 0 }),
                    fallbackGeneration,
                    expectedSession = s,
                )
            } finally {
                synchronized(stateLock) {
                    if (sessionGeneration == fallbackGeneration) {
                        _switching.value = false
                        if (fallbackJob === coroutineContext[Job]) fallbackJob = null
                    }
                }
            }
        }
        synchronized(stateLock) {
            // The generation may have been invalidated before launch was installed (e.g. a fast
            // stop/replay on another dispatcher); cancel in that case instead of retaining it.
            if (sessionGeneration == fallbackGeneration && !released) {
                fallbackJob = launched
            } else {
                launched.cancel()
            }
        }
    }

    private fun invalidatePendingFallbackLocked() {
        fallbackJob?.cancel()
        fallbackJob = null
        sessionGeneration += 1
        fellBackThisSession = false
        _switching.value = false
    }

    private fun isCurrentSession(
        expectedSession: PlaybackSession,
        expectedGeneration: Long,
        expectedEngine: PlaybackEnginePort? = null,
    ): Boolean = synchronized(stateLock) {
        isCurrentSessionLocked(expectedSession, expectedGeneration, expectedEngine)
    }

    private fun isCurrentSessionLocked(
        expectedSession: PlaybackSession,
        expectedGeneration: Long,
        expectedEngine: PlaybackEnginePort? = null,
    ): Boolean = !released &&
        sessionGeneration == expectedGeneration &&
        session === expectedSession &&
        (expectedEngine == null || current === expectedEngine)

    private companion object {
        /** mpv 可能救回的错误类型；网络/鉴权/DRM 不降级（同一网络与凭据救不回）。 */
        val FALLBACK_ERROR_CODES = setOf(
            PlaybackError.Code.DECODER_ERROR,
            PlaybackError.Code.UNSUPPORTED_CODEC,
            PlaybackError.Code.UNKNOWN,
        )
    }
}
