package com.mediahub.player.engine

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.PlaybackError
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackProgressReason
import com.mediahub.model.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Media3 播放引擎：高频 UI 状态、周期进度与关键事件分流输出。 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    private val player: ExoPlayer,
    private val playerFactory: PlayerFactory,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow<PlaybackProgress?>(null)
    val progress: StateFlow<PlaybackProgress?> = _progress.asStateFlow()

    private val criticalEvents = CriticalPlaybackEventQueue()
    val progressEvents = criticalEvents.events

    private val trackSelector: DefaultTrackSelector =
        requireNotNull(player.trackSelector as? DefaultTrackSelector) {
            "ExoPlayer 必须配置 DefaultTrackSelector"
        }

    private var session: PlaybackSession? = null
    private var progressJob: Job? = null
    private var endedReported = false

    val exoPlayer: ExoPlayer get() = player

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isEnded = playbackState == Player.STATE_ENDED
                _uiState.update {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        isEnded = isEnded,
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf { duration -> duration > 0 } ?: it.durationMs,
                    )
                }
                if (isEnded && !endedReported) {
                    endedReported = true
                    publishCritical(PlaybackProgressReason.END)
                    progressJob?.cancel()
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
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _uiState.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
            }
        })
    }

    fun play(session: PlaybackSession) {
        if (this.session != null) stop()
        this.session = session
        endedReported = false
        _uiState.value = PlaybackUiState(mediaTitle = session.itemTitle)

        val mediaItem = session.source.toMedia3Item(session)
        val requestContext = PlaybackRequestContext.from(session.source)
        player.setMediaSource(playerFactory.createMediaSource(mediaItem, requestContext))
        val startPosition = session.startPositionMs ?: session.resumePositionMs
        if (startPosition != null && startPosition > 0) player.seekTo(startPosition)
        player.prepare()
        player.play()
        updateProgressSnapshot()
        publishCritical(PlaybackProgressReason.PLAY, isPaused = false)
        startProgressLoop()
        logger.i(LogTag.PLAYER, "开始播放 serverId=${session.serverId} itemId=${session.itemId}")
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else resume()
    }

    fun pause() {
        if (!player.playWhenReady) return
        player.pause()
        updateProgressSnapshot()
        publishCritical(PlaybackProgressReason.PAUSE, isPaused = true)
    }

    fun resume() {
        if (player.playWhenReady) return
        player.play()
        updateProgressSnapshot()
        publishCritical(PlaybackProgressReason.PLAY, isPaused = false)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        updateProgressSnapshot()
        publishCritical(PlaybackProgressReason.SEEK)
    }

    fun stop(publishEvent: Boolean = true) {
        if (session == null) return
        updateProgressSnapshot()
        if (publishEvent) publishCritical(PlaybackProgressReason.STOP)
        progressJob?.cancel()
        player.stop()
        session = null
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3f)
        player.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(speed = clamped) }
    }

    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
        _uiState.update { it.copy(volume = player.volume) }
    }

    fun selectAudioTrack(selection: TrackSelection?) = selectTrack(C.TRACK_TYPE_AUDIO, selection)

    fun selectSubtitleTrack(selection: TrackSelection?) = selectTrack(C.TRACK_TYPE_TEXT, selection)

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

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                updateProgressSnapshot()
                delay(UI_PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun updateProgressSnapshot(): PlaybackProgress? {
        val progress = currentProgress() ?: return null
        _progress.value = progress
        _uiState.update {
            it.copy(
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                isSeekable = player.isCurrentMediaItemSeekable,
            )
        }
        return progress
    }

    fun currentProgress(isPaused: Boolean? = null): PlaybackProgress? {
        val active = session ?: return null
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        return PlaybackProgress(
            serverId = active.serverId,
            itemId = active.itemId,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            isPaused = isPaused ?: !player.playWhenReady,
            updatedAtEpochMs = System.currentTimeMillis(),
            mode = active.source.mode,
            itemTitle = active.itemTitle,
            itemType = active.itemType,
        )
    }

    private fun publishCritical(reason: PlaybackProgressReason, isPaused: Boolean? = null) {
        val progress = currentProgress(isPaused) ?: return
        _progress.value = progress
        criticalEvents.offer(PlaybackProgressEvent(progress, reason))
    }

    /** 返回释放前最后一份进度，供 ViewModel 做兜底 final flush。 */
    fun release(): PlaybackProgress? {
        val finalProgress = currentProgress()
        progressJob?.cancel()
        session = null
        criticalEvents.close()
        player.release()
        logger.i(LogTag.PLAYER, "播放引擎已释放")
        return finalProgress
    }

    private fun PlaybackSource.toMedia3Item(session: PlaybackSession): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("${session.serverId}/${session.itemId}")
            .setMimeType(mimeType)
            .build()

    private fun mapPlayerError(error: PlaybackException): PlaybackError {
        val details = mapOf(
            "media3ErrorCode" to error.errorCode.toString(),
            "message" to (error.message ?: ""),
        )
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            -> PlaybackError(PlaybackError.Code.DECODER_ERROR, details = details, cause = error)

            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            -> PlaybackError(PlaybackError.Code.DRM_ERROR, details = details, cause = error)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            -> PlaybackError(PlaybackError.Code.NETWORK_TIMEOUT, details = details, cause = error)

            else -> PlaybackError(PlaybackError.Code.UNKNOWN, details = details, cause = error)
        }
    }

    private companion object {
        const val UI_PROGRESS_INTERVAL_MS = 500L
    }
}
