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
 * - uiState / progress / events / subtitleCues / downloadSpeedBps 均由当前内部引擎转发；
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

    private var current: PlaybackEnginePort? = null
    private var forwardJob: Job? = null
    private var session: PlaybackSession? = null
    private var attachedSurface: Surface? = null
    private var fellBackThisSession = false
    private var released = false

    override val kind: EngineKind
        get() = current?.kind ?: EngineKind.MEDIA3

    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        current?.attachSurface(surface)
    }

    override fun play(session: PlaybackSession) {
        this.session = session
        val mode = modeProvider()
        val selection = PlaybackEngineSelector.select(
            source = session.source,
            mode = mode,
            mpvPreferredSignatures = history.mpvPreferredSignatures(),
        )
        logger.i(LogTag.PLAYER, "引擎选择 kind=${selection.kind} mode=$mode reason=${selection.reason}")
        startEngine(selection.kind, session)
    }

    override fun togglePlayPause() {
        current?.togglePlayPause()
    }

    override fun seekTo(positionMs: Long) {
        current?.seekTo(positionMs)
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

    override fun stop(): PlaybackProgress? = current?.stop()

    override fun release() {
        if (released) return
        released = true
        forwardJob?.cancel()
        current?.release()
        current = null
    }

    // ---- 内部 ----

    private fun startEngine(kind: EngineKind, session: PlaybackSession) {
        val engine = (if (kind == EngineKind.MPV) mpvFactory else media3Factory).create(scope)
        current = engine
        _engineKind.value = kind
        // 切引擎后清空旧错误/轨道状态，避免 Media3 的错误残留显示在 mpv 上
        _uiState.value = PlaybackUiState(durationMs = session.source.durationMs ?: 0, mediaTitle = session.itemTitle)
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch { engine.uiState.collect { _uiState.value = it } }
            launch { engine.progress.collect { _progress.tryEmit(it) } }
            launch { engine.events.collect { _events.trySend(it) } }
            launch { engine.subtitleCues.collect { _subtitleCues.value = it } }
            launch { engine.downloadSpeedBps.collect { _downloadSpeedBps.value = it } }
        }
        if (kind == EngineKind.MEDIA3) {
            scope.launch { watchMedia3Failure(engine) }
        }
        attachedSurface?.let { engine.attachSurface(it) }
        engine.play(session)
    }

    /** 仅监听"由本 engine 实例引发的"失败；引擎切换后旧实例的流不再触发降级。 */
    private suspend fun watchMedia3Failure(engine: PlaybackEnginePort) {
        // 错误路径：解码器 / 不支持编码 / 解析类未知错误 → 立即降级
        launchErrorWatch(engine)
        // 无声路径：有音轨但无音频输出信号，经宽限期（collectLatest 取消语义）确认后降级
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
                fallbackToMpv(engine, "有音轨但无音频输出（${state.audioTracks.firstOrNull()?.codec ?: "?"}）")
            }
        }
    }

    private fun launchErrorWatch(engine: PlaybackEnginePort) {
        scope.launch {
            engine.uiState.collect { state ->
                if (current !== engine || released || fellBackThisSession) return@collect
                if (modeProvider() != PlaybackEngineMode.AUTO) return@collect
                val error = state.error ?: return@collect
                if (error.code in FALLBACK_ERROR_CODES) {
                    fallbackToMpv(engine, "播放错误 ${error.code.name}")
                }
            }
        }
    }

    private fun fallbackToMpv(failedEngine: PlaybackEnginePort, reason: String) {
        val s = session ?: return
        synchronized(this) {
            if (fellBackThisSession || released) return
            fellBackThisSession = true
        }
        _switching.value = true
        scope.launch {
            try {
                history.recordMedia3Failure(CompatibilitySignature.from(s.source).key)
                // 保存当前位置（先取 stop() 返回值，兜底 uiState）
                val position = failedEngine.stop()?.positionMs
                    ?: _uiState.value.positionMs
                failedEngine.release()
                logger.i(
                    LogTag.PLAYER,
                    "自动降级 mpv reason=$reason resumeMs=$position signature=${CompatibilitySignature.from(s.source).key}",
                )
                startEngine(
                    EngineKind.MPV,
                    s.copy(startPositionMs = position.takeIf { it > 0 }),
                )
            } finally {
                _switching.value = false
            }
        }
    }

    private companion object {
        /** mpv 可能救回的错误类型；网络/鉴权/DRM 不降级（同一网络与凭据救不回）。 */
        val FALLBACK_ERROR_CODES = setOf(
            PlaybackError.Code.DECODER_ERROR,
            PlaybackError.Code.UNSUPPORTED_CODEC,
            PlaybackError.Code.UNKNOWN,
        )
    }
}
