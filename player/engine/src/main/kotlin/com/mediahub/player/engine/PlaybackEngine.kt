@file:OptIn(UnstableApi::class)

package com.mediahub.player.engine

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
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
 * 播放引擎（Media3 / ExoPlayer 封装）。
 *
 * - 播放源 → Media3 MediaItem（URI + MIME；请求头经本引擎私有的
 *   [PlaybackHeadersHolder] 注入，见 ADR-018：不同引擎互不污染）；
 * - UI 状态流（播放/缓冲/进度/轨道/错误）；
 * - 音轨/字幕选择（DefaultTrackSelector）；
 * - [progress] 每秒进度流 + [events] 关键事件流（供进度同步管线，见 ADR-017）；
 * - 结构化错误映射（PlaybackException → PlaybackError）。
 *
 * 不持有 Android 生命周期；由创建方（ViewModel）负责 release()。
 */
class PlaybackEngine(
    private val player: ExoPlayer,
    private val headersHolder: PlaybackHeadersHolder,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : PlaybackEnginePort {
    private val _uiState = MutableStateFlow(PlaybackUiState())
    override val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private val _progress = MutableSharedFlow<PlaybackProgress>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** 每秒进度流（低频消费方请自行 sample/节流）。 */
    override val progress: SharedFlow<PlaybackProgress> = _progress.asSharedFlow()

    // 关键事件通道：改 Channel(UNLIMITED)（review P2-3），保证 Pause/Seek/Resume 快速
    // 连续发生时逐条保留、严格保序、不被 conflate/drop。周期性 position 仍由 StateFlow conflate。
    private val _events = Channel<PlaybackEvent>(Channel.UNLIMITED)

    /** 关键事件流（Pause/Seek/Ended/Stopped）。 */
    override val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private val trackSelector: DefaultTrackSelector =
        requireNotNull(player.trackSelector as? DefaultTrackSelector) {
            "ExoPlayer 必须配置 DefaultTrackSelector"
        }

    private var session: PlaybackSession? = null
    private var progressJob: Job? = null
    private var released = false

    /** 供 PlayerView 绑定。 */
    override val exoPlayer: ExoPlayer get() = player

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        isEnded = playbackState == Player.STATE_ENDED,
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf { d -> d > 0 } ?: it.durationMs,
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    _events.trySend(PlaybackEvent.Ended)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val mapped = mapPlayerError(error)
                logger.e(LogTag.PLAYER, "播放错误 code=${error.errorCode} msg=${error.message}")
                _uiState.update { it.copy(error = mapped, isBuffering = false) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val mapped = TrackMapper.mapTracks(tracks)
                _uiState.update {
                    it.copy(
                        audioTracks = mapped.audioTracks,
                        subtitleTracks = mapped.subtitleTracks,
                        selectedAudio = mapped.selectedAudio,
                        selectedSubtitle = mapped.selectedSubtitle,
                        audioFormatMime = player.audioFormat?.sampleMimeType,
                    )
                }
            }



            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _uiState.update {
                    it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
                }
            }
        })
        // 音频输出信号（无声判据，Phase 1B-2.4）：AnalyticsListener 才有 onAudioFormatChanged
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                _uiState.update { it.copy(audioFormatMime = format.sampleMimeType) }
            }
        })
    }

    override fun play(session: PlaybackSession) {
        this.session = session
        headersHolder.setHeaders(buildRequestHeaders(session.source))
        val mediaItem = session.source.toMedia3Item(session)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        val startPosition = session.startPositionMs ?: session.resumePositionMs
        if (startPosition != null && startPosition > 0) {
            player.seekTo(startPosition)
        }
        _uiState.value = PlaybackUiState(mediaTitle = session.itemTitle)
        startProgressLoop()
        logger.i(LogTag.PLAYER, "开始播放 serverId=${session.serverId} itemId=${session.itemId}")
    }

    // ---- 控制 ----

    override fun togglePlayPause() {
        if (player.isPlaying) pause() else resume()
    }

    fun pause() {
        player.pause()
        _events.trySend(PlaybackEvent.Paused)
    }

    fun resume() {
        _events.trySend(PlaybackEvent.Resumed)
        player.play()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        _events.trySend(PlaybackEvent.Seeked)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3f)
        player.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(speed = clamped) }
    }

    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
        _uiState.update { it.copy(volume = player.volume) }
    }

    override fun selectAudioTrack(selection: TrackSelection?) {
        selectTrack(C.TRACK_TYPE_AUDIO, selection)
    }

    override fun selectSubtitleTrack(selection: TrackSelection?) {
        selectTrack(C.TRACK_TYPE_TEXT, selection)
    }

    private fun selectTrack(rendererType: Int, selection: TrackSelection?) {
        val mapped = trackSelector.currentMappedTrackInfo ?: return
        val groups = mapped.getTrackGroups(rendererType)
        val builder = trackSelector.buildUponParameters()

        if (selection == null) {
            builder.setRendererDisabled(rendererType, true)
        } else {
            if (selection.groupIndex !in 0 until groups.length) return
            builder.setRendererDisabled(rendererType, false)
            builder.setSelectionOverride(
                rendererType,
                groups,
                DefaultTrackSelector.SelectionOverride(selection.groupIndex, selection.trackIndex),
            )
        }
        trackSelector.setParameters(builder)
    }

    // ---- 进度 ----

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val position = player.currentPosition
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                _uiState.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        isSeekable = player.isCurrentMediaItemSeekable,
                    )
                }
                session?.let { s ->
                    _progress.tryEmit(
                        PlaybackProgress(
                            serverId = s.serverId,
                            itemId = s.itemId,
                            positionMs = position,
                            durationMs = duration,
                            isPaused = !player.playWhenReady,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            mode = s.source.mode,
                            itemTitle = s.itemTitle,
                            itemType = s.itemType,
                            posterUrl = s.posterUrl,
                        )
                    )
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    /**
     * 立即读取当前进度（不依赖每秒循环，退出瞬间取值用）。
     */
    fun currentProgress(): PlaybackProgress? = session?.let { s ->
        PlaybackProgress(
            serverId = s.serverId,
            itemId = s.itemId,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            isPaused = !player.playWhenReady,
            updatedAtEpochMs = System.currentTimeMillis(),
            mode = s.source.mode,
            itemTitle = s.itemTitle,
            itemType = s.itemType,
            posterUrl = s.posterUrl,
        )
    }

    /**
     * 停止播放（显式退出状态机第一步，见 ADR-023）：
     * 1. 停止每秒进度循环（不再产生新 tick）；
     * 2. 发出 Stopped 事件（进度协调器收到后立即 flush）；
     * 3. 返回最终进度（调用方用于显式 final flush）。
     */
    override fun stop(): PlaybackProgress? {
        progressJob?.cancel()
        val finalProgress = currentProgress()
        _events.trySend(PlaybackEvent.Stopped)
        logger.i(LogTag.PLAYER, "播放停止 serverId=${session?.serverId} itemId=${session?.itemId}")
        return finalProgress
    }

    override fun release() {
        if (released) return
        released = true
        progressJob?.cancel()
        headersHolder.setHeaders(emptyMap())
        player.release()
        logger.i(LogTag.PLAYER, "播放引擎已释放")
    }

    // ---- 映射 ----

    private fun buildRequestHeaders(source: PlaybackSource): Map<String, String> {
        val headers = source.headers.toMutableMap()
        source.cookies.takeIf { it.isNotEmpty() }?.let { cookieMap ->
            headers["Cookie"] = cookieMap.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }
        return headers
    }

    private fun PlaybackSource.toMedia3Item(session: PlaybackSession): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("${session.serverId}/${session.itemId}")
            .setMimeType(mimeType)
            .build()

    private fun mapPlayerError(e: PlaybackException): PlaybackError {
        val details = mapOf(
            "media3ErrorCode" to e.errorCode.toString(),
            "message" to (e.message ?: ""),
        )
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            -> PlaybackError(PlaybackError.Code.DECODER_ERROR, details = details, cause = e)

            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            -> PlaybackError(PlaybackError.Code.DRM_ERROR, details = details, cause = e)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            -> PlaybackError(PlaybackError.Code.NETWORK_TIMEOUT, details = details, cause = e)

            else -> PlaybackError(PlaybackError.Code.UNKNOWN, details = details, cause = e)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
