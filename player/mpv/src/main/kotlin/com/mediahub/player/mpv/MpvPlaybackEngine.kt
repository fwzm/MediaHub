package com.mediahub.player.mpv

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MpvHttpBridge
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.TrackSelection
import dev.jdtech.mpv.MPVLib
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * mpv 兼容播放引擎（U2，EngineKind.MPV）。
 *
 * 走 MpvHttpBridge（localhost 代理 + ADR-030），DTS-HD/TrueHD 由 FFmpeg 解码为 PCM 走 AudioTrack，
 * MPEG-TS 等 Media3 失败容器由 libmpv extractor 兜底。TTFF 埋点：requested/file-loaded/video-reconfig/audio-reconfig。
 */
class MpvPlaybackEngine(
    private val context: Context,
    private val logger: Logger,
    private val scope: CoroutineScope,
    httpClientFactory: HttpClientFactory,
) : PlaybackEnginePort {

    override val kind: EngineKind = EngineKind.MPV

    private val _uiState = MutableStateFlow(PlaybackUiState())
    override val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val progress: SharedFlow<PlaybackProgress> = _progress.asSharedFlow()

    private val _events = Channel<PlaybackEvent>(Channel.UNLIMITED)
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    // mpv 内部 libass 渲染字幕，不向外发 cues
    private val _subtitleCues = MutableStateFlow<CueGroup?>(null)
    override val subtitleCues: StateFlow<CueGroup?> = _subtitleCues.asStateFlow()

    override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)

    private val httpClientFactory = httpClientFactory
    private var mpv: MPVLib? = null
    private var bridge: MpvHttpBridge? = null
    private var session: PlaybackSession? = null
    private var progressJob: Job? = null
    private var attachedSurface: Surface? = null
    private var playRequestedAtMs = 0L
    private var released = false

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "pause") _uiState.update { it.copy(isPlaying = !value) }
        }
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> _uiState.update { it.copy(positionMs = (value * 1000).toLong()) }
                "duration" -> if (value > 0) _uiState.update { it.copy(durationMs = (value * 1000).toLong()) }
                "speed" -> _uiState.update { it.copy(speed = value.toFloat()) }
            }
        }
        override fun eventProperty(property: String, value: String) {
            if (property == "media-title") _uiState.update { it.copy(mediaTitle = value) }
        }
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ->
                    logger.i(LogTag.PLAYER, "mpv file-loaded ttff=" + (SystemClock.elapsedRealtime() - playRequestedAtMs) + "ms")
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG ->
                    logger.i(LogTag.PLAYER, "mpv video-reconfig ttff=" + (SystemClock.elapsedRealtime() - playRequestedAtMs) + "ms")
                MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG ->
                    logger.i(LogTag.PLAYER, "mpv audio-reconfig ttff=" + (SystemClock.elapsedRealtime() - playRequestedAtMs) + "ms")
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> _events.trySend(PlaybackEvent.Ended)
            }
        }
    }

    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        val m = mpv
        if (m != null) {
            if (surface != null) m.attachSurface(surface) else m.detachSurface()
        }
    }

    override fun play(session: PlaybackSession) {
        this.session = session
        val src = session.source
        scope.launch {
            playRequestedAtMs = SystemClock.elapsedRealtime()
            try {
                val b = MpvHttpBridge(httpClientFactory)
                bridge = b
                val bridgeUrl = b.start(src.url, buildHeaders(src))
                val m = MPVLib.create(context) ?: throw IllegalStateException("mpv create failed")
                mpv = m
                m.addObserver(observer)
                // 兼容优先：硬解失败回退软解；DTS-HD/TrueHD 解码为 PCM 走 AudioTrack
                m.setOptionString("vo", "gpu")
                m.setOptionString("ao", "audiotrack")
                m.setOptionString("hwdec", "mediacodec")
                // MPEG-TS 等 FFmpeg 无法从 application/octet-stream 自动探测的容器：按 container 强制 demuxer
                val container = src.container?.lowercase()
                if (container == "mpegts" || container == "ts" || container == "m2ts") {
                    m.setOptionString("demuxer-lavf-format", "mpegts")
                }
                logger.i(LogTag.PLAYER, "mpv container=" + container + " video=" + src.videoCodec + " audio=" + src.audioCodec)
                m.init()
                attachedSurface?.let { m.attachSurface(it) }
                m.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                m.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                m.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                m.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                m.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                _uiState.value = PlaybackUiState(durationMs = src.durationMs ?: 0, mediaTitle = session.itemTitle)
                m.command(arrayOf("loadfile", bridgeUrl))
                startProgressLoop()
                logger.i(LogTag.PLAYER, "mpv 开始播放 serverId=" + session.serverId + " itemId=" + session.itemId)
            } catch (e: Exception) {
                logger.e(LogTag.PLAYER, "mpv 起播失败", e)
                _uiState.update { it.copy(error = PlaybackError(PlaybackError.Code.UNKNOWN, details = mapOf("msg" to (e.message ?: "")), cause = e)) }
            }
        }
    }

    override fun togglePlayPause() {
        val m = mpv ?: return
        val playing = _uiState.value.isPlaying
        m.setPropertyBoolean("pause", playing)
        _events.trySend(if (playing) PlaybackEvent.Paused else PlaybackEvent.Resumed)
    }

    override fun seekTo(positionMs: Long) {
        val m = mpv ?: return
        m.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
        _events.trySend(PlaybackEvent.Seeked)
    }

    override fun setSpeed(speed: Float) {
        mpv?.setPropertyDouble("speed", speed.toDouble())
        _uiState.update { it.copy(speed = speed) }
    }

    override fun selectAudioTrack(selection: TrackSelection?) = Unit
    override fun selectSubtitleTrack(selection: TrackSelection?) = Unit

    override fun stop(): PlaybackProgress? {
        progressJob?.cancel()
        val final = currentProgress()
        _events.trySend(PlaybackEvent.Stopped)
        return final
    }

    override fun release() {
        if (released) return
        released = true
        progressJob?.cancel()
        mpv?.destroy()
        mpv = null
        bridge?.stop()
        bridge = null
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val m = mpv ?: return@launch
                val pos = (m.getPropertyDouble("time-pos") ?: 0.0) * 1000
                val dur = (m.getPropertyDouble("duration") ?: 0.0) * 1000
                val paused = m.getPropertyBoolean("pause") ?: false
                _uiState.update {
                    it.copy(positionMs = pos.toLong(), durationMs = if (dur > 0) dur.toLong() else it.durationMs, isEnded = m.getPropertyBoolean("eof-reached") ?: false)
                }
                session?.let { s ->
                    _progress.tryEmit(
                        PlaybackProgress(
                            serverId = s.serverId, itemId = s.itemId, positionMs = pos.toLong(),
                            durationMs = if (dur > 0) dur.toLong() else (s.source.durationMs ?: 0),
                            isPaused = paused, updatedAtEpochMs = System.currentTimeMillis(),
                            mode = s.source.mode, itemTitle = s.itemTitle, itemType = s.itemType, posterUrl = s.posterUrl,
                        )
                    )
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun currentProgress(): PlaybackProgress? = session?.let { s ->
        val m = mpv
        val pos = ((m?.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()
        val dur = ((m?.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
        PlaybackProgress(
            serverId = s.serverId, itemId = s.itemId, positionMs = pos,
            durationMs = if (dur > 0) dur else (s.source.durationMs ?: 0),
            isPaused = m?.getPropertyBoolean("pause") ?: false, updatedAtEpochMs = System.currentTimeMillis(),
            mode = s.source.mode, itemTitle = s.itemTitle, itemType = s.itemType, posterUrl = s.posterUrl,
        )
    }

    private fun buildHeaders(source: PlaybackSource): Map<String, String> {
        val headers = source.headers.toMutableMap()
        source.cookies.takeIf { it.isNotEmpty() }?.let { cookieMap ->
            headers["Cookie"] = cookieMap.entries.joinToString("; ") { (k, v) -> k + "=" + v }
        }
        return headers
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}