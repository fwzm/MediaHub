@file:OptIn(UnstableApi::class)

package com.mediahub.player.engine

import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val speedMonitor: PlaybackSpeedMonitor,
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
    private var playbackGeneration = 0L
    private var subtitleOperation = 0L
    private var progressJob: Job? = null
    private var released = false
    /** Visualizer 是按 UI/lifecycle 需求启用的重资源，默认不创建。 */
    private var audioSpectrumEnabled = false
    /** stop 后拒绝迟到的 audio-session 回调重新拉起采样。 */
    private var audioSpectrumSessionActive = false
    /** 起播时间戳（elapsedRealtime），用于 TTFF（首帧）诊断。 */
    private var playStartElapsedMs = 0L

    override val kind: EngineKind = EngineKind.MEDIA3

    /** 真实媒体下载速度（B/s，TransferListener 统计）。 */
    override val downloadSpeedBps: StateFlow<Long> = speedMonitor.bytesPerSecond

    private val _subtitleCues = MutableStateFlow<CueGroup?>(null)
    override val subtitleCues: StateFlow<CueGroup?> = _subtitleCues.asStateFlow()

    private val audioSpectrumController = AudioSpectrumSessionController(
        captureFactory = AndroidVisualizerCaptureFactory,
        onFailure = { failure ->
            logger.w(LogTag.PLAYER, "音频频谱采样不可用，已降级为基础动画", failure)
        },
    )
    override val audioBands: StateFlow<AudioBandLevels?> = audioSpectrumController.audioBands

    override fun attachSurface(surface: Surface?) {
        player.setVideoSurface(surface)
    }

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
                if (isPlaying) {
                    session?.trace?.record(PlaybackStartupTrace.Milestone.PLAYING)
                }
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    session?.trace?.record(PlaybackStartupTrace.Milestone.ENGINE_READY)
                }
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

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSpectrumEnabled && audioSpectrumSessionActive && audioSessionId > 0) {
                    audioSpectrumController.bind(audioSessionId)
                } else {
                    audioSpectrumController.clear()
                }
            }



            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _uiState.update {
                    it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
                }
            }

            override fun onCues(cues: CueGroup) {
                _subtitleCues.value = cues
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

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                session?.trace?.record(PlaybackStartupTrace.Milestone.VIDEO_DECODER_INITIALIZED)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                session?.trace?.record(PlaybackStartupTrace.Milestone.AUDIO_DECODER_INITIALIZED)
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                session?.trace?.record(PlaybackStartupTrace.Milestone.FIRST_FRAME_RENDERED)
                if (playStartElapsedMs > 0) {
                    val ttff = SystemClock.elapsedRealtime() - playStartElapsedMs
                    logger.i(LogTag.PLAYER, "首帧渲染 ttff=" + ttff + "ms renderTimeMs=" + renderTimeMs)
                    session?.trace?.let { t ->
                        logger.i(LogTag.PLAYER, "StartupTrace " + t.summary())
                    }
                }
            }
        })
    }

    override fun play(session: PlaybackSession) {
        if (released) return
        playbackGeneration++
        subtitleOperation++
        // 同一个 ExoPlayer 可复用 audio session；每次媒体会话仍先释放旧 capture，防止迟到回调串流。
        audioSpectrumController.clear()
        audioSpectrumSessionActive = true
        this.session = session
        // 新媒体会话：外挂字幕与手动字幕轨选择不跨会话携带（匹配记忆由 ViewModel 层重放）。
        externalSubtitles.clear()
        lastSelectedSubtitle = null
        clearTextSelectionOverrides()
        session.trace?.record(PlaybackStartupTrace.Milestone.MEDIA_REQUEST_STARTED)
        session.trace?.record(PlaybackStartupTrace.Milestone.ENGINE_PREPARE_STARTED)
        headersHolder.setHeaders(buildRequestHeaders(session.source))
        val mediaItem = session.source.toMedia3Item(session)
        player.setMediaItem(mediaItem)
        player.prepare()
        retryAudioSpectrumCapture()
        player.playWhenReady = true
        val startPosition = session.startPositionMs ?: session.resumePositionMs
        if (startPosition != null && startPosition > 0) {
            player.seekTo(startPosition)
        }
        playStartElapsedMs = SystemClock.elapsedRealtime()
        speedMonitor.reset()
        // 临时时长：Media3 timeline READY 前先展示 source 时长（Emby runTimeTicks），避免 0:00/满条
        _uiState.value = PlaybackUiState(
            durationMs = session.source.durationMs ?: 0,
            mediaTitle = session.itemTitle,
        )
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

    override fun seekTo(positionMs: Long, mode: SeekMode) {
        player.seekTo(positionMs.coerceAtLeast(0))
        // PREVIEW（手势拖动/连续快退节流）不发 Seeked，避免远端即时同步风暴（U3-B）
        if (mode == SeekMode.COMMIT) _events.trySend(PlaybackEvent.Seeked)
    }

    override fun setSpeed(speed: Float) {
        // 上限 5.0 对齐长按倍速手势阶梯（U3-B）；下限 0.1 对齐 0.1× 档位
        val clamped = speed.coerceIn(0.1f, 5f)
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
        subtitleOperation++
        selectTrack(C.TRACK_TYPE_TEXT, selection)
    }

    // ---- 外挂字幕（P2 字幕中心切片一） ----

    /**
     * Media3 能力矩阵（如实自述）：
     * - 外挂字幕 = 支持（[MediaItem.SubtitleConfiguration] 侧挂 + 媒体项重建，可能瞬断）；
     * - 偏移 = **不支持**（Media3 无公开字幕偏移 API；偏移仅 mpv `sub-delay`，UI 已标注）。
     */
    override val subtitleCapabilities: SubtitleCapabilities =
        SubtitleCapabilities(externalLoad = true, offsetAdjust = false)

    override val subtitleOffsetMs: Long get() = 0L

    /** 已侧挂的外挂字幕（重建媒体项时全部重挂）。 */
    private val externalSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()

    /** 最近的手选内嵌字幕轨（外挂尝试失败时与原 selector 参数一起恢复；null=关闭/未选）。 */
    private var lastSelectedSubtitle: TrackSelection? = null

    override suspend fun loadExternalSubtitle(subtitle: ExternalSubtitle): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (released) return false
        val expectedSession = session ?: return false
        val generation = playbackGeneration
        val operation = subtitleOperation + 1
        val configuration = subtitle.toSubtitleConfiguration("external-$generation-$operation") ?: return false
        subtitleOperation = operation
        fun currentRequest() = !released && session === expectedSession && playbackGeneration == generation && subtitleOperation == operation
        val previousSelection = lastSelectedSubtitle
        val previousParameters = trackSelector.parameters
        var accepted = false
        try {
            context.ensureActive()
            lastSelectedSubtitle = null
            clearTextSelectionOverrides()
            // Pending configurations are not retained after failure/cancellation; the next load
            // cannot reattach an unconfirmed resource. Unique format IDs reject old Tracks events.
            rebuildMediaItemWithExternalSubtitles(externalSubtitles.filter { it.uri != configuration.uri } + configuration)
            repeat(EXTERNAL_SUBTITLE_CONFIRM_ATTEMPTS) { attempt ->
                context.ensureActive()
                if (!currentRequest()) return false
                val tracks = player.currentTracks
                // DefaultMediaSourceFactory merges the main source at slot 0 and sidecars at
                // configuration index + 1. Actual MergingMediaPeriod namespaces Format.id too.
                // Bind the full expected ID to the exact current configuration, never a suffix.
                val configurationIndex = player.currentMediaItem?.localConfiguration?.subtitleConfigurations
                    ?.indexOf(configuration) ?: -1
                val childNamespace = "${configurationIndex + 1}:"
                val mergedFormatId = childNamespace + configuration.id
                val target = if (configurationIndex < 0) null else tracks.groups.firstNotNullOfOrNull { group ->
                    if (group.type != C.TRACK_TYPE_TEXT || !group.mediaTrackGroup.id.startsWith(childNamespace)) null
                    else (0 until group.length).firstOrNull { group.getTrackFormat(it).id == mergedFormatId }
                        ?.let { index -> group to index }
                }
                if (target != null) {
                    val (group, index) = target
                    if (!group.isTrackSupported(index)) return false
                    if (group.isTrackSelected(index)) {
                        context.ensureActive()
                        if (!currentRequest()) return false
                        externalSubtitles.removeAll { it.uri == configuration.uri }
                        externalSubtitles += configuration
                        accepted = true
                        return true
                    }
                    context.ensureActive()
                    if (!currentRequest()) return false
                    trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)))
                }
                if (attempt < EXTERNAL_SUBTITLE_CONFIRM_ATTEMPTS - 1) delay(EXTERNAL_SUBTITLE_CONFIRM_INTERVAL_MS)
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(LogTag.PLAYER, "Media3 external subtitle confirmation failed")
            return false
        } finally {
            if (!accepted && currentRequest()) {
                // Remove a late pending subtitle from the actual media item too. Do not roll back
                // over an Off/new request/new media session that already superseded this request.
                lastSelectedSubtitle = previousSelection
                // Preserve the original source's group identities. Replaying indices against
                // the pending merged mapping would replace them with stale namespaced groups.
                trackSelector.setParameters(previousParameters)
                runCatching { rebuildMediaItemWithExternalSubtitles() }
                    .onFailure { logger.w(LogTag.PLAYER, "Media3 subtitle rollback failed") }
            }
        }
    }

    private fun clearTextSelectionOverrides() {
        val builder = trackSelector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        trackSelector.currentMappedTrackInfo?.let { mapped ->
            for (renderer in 0 until mapped.rendererCount) {
                if (mapped.getRendererType(renderer) == C.TRACK_TYPE_TEXT) {
                    builder.clearSelectionOverrides(renderer)
                    builder.setRendererDisabled(renderer, false)
                }
            }
        }
        trackSelector.setParameters(builder)
    }

    private fun ExternalSubtitle.toSubtitleConfiguration(nativeId: String): MediaItem.SubtitleConfiguration? {
        val uri = try {
            android.net.Uri.parse(uri)
        } catch (e: Exception) {
            return null
        }
        if (mimeType !in SUPPORTED_SUBTITLE_MIMES) return null
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLabel(name)
            .setLanguage(language)
            .setId(nativeId)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /**
     * 媒体项重建纪律（沿用引擎切换的"保存位置同位重播"语义）：
     * 捕获 位置/暂停/倍速 → setMediaItem(item, position) → prepare() → 恢复 暂停态与倍速。
     * `setMediaItem(item, positionMs)` 在同一调用内完成换源与 seek，避免双跳帧；
     * 倍速/playWhenReady 在 Media3 中跨 setMediaItem 保留，此处仍显式重施（防御版本差异）。
     */
    private fun rebuildMediaItemWithExternalSubtitles(configurations: List<MediaItem.SubtitleConfiguration> = externalSubtitles.toList()) {
        val s = session ?: return
        val snapshot = PlaybackRestoreSnapshot(
            positionMs = player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady,
            speed = player.playbackParameters.speed,
        )
        val mediaItem = s.source.toMedia3Item(s)
            .buildUpon()
            .setSubtitleConfigurations(configurations)
            .build()
        player.setMediaItem(mediaItem, snapshot.positionMs)
        player.prepare()
        player.playWhenReady = snapshot.playWhenReady
        player.setPlaybackSpeed(snapshot.speed)
    }


    override fun setAudioSpectrumEnabled(enabled: Boolean) {
        if (released) return
        audioSpectrumEnabled = enabled
        if (enabled && audioSpectrumSessionActive) {
            audioSpectrumController.bind(player.audioSessionId)
        } else {
            audioSpectrumController.clear()
        }
    }

    /** RECORD_AUDIO 授权可能晚于播放器创建；按需用当前有效 audio session 立即重试。 */
    override fun retryAudioSpectrumCapture() {
        if (released || !audioSpectrumEnabled || !audioSpectrumSessionActive) return
        audioSpectrumController.bind(player.audioSessionId)
    }

    private fun selectTrack(trackType: Int, selection: TrackSelection?) {
        val mapped = trackSelector.currentMappedTrackInfo
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(trackType)
            .setTrackTypeDisabled(trackType, selection == null)
        if (mapped == null) {
            if (selection == null) {
                if (trackType == C.TRACK_TYPE_TEXT) lastSelectedSubtitle = null
                trackSelector.setParameters(builder)
            }
            return
        }
        // Media3 renderer indices depend on the installed renderer order; C.TRACK_TYPE_* are types.
        val rendererIndices = (0 until mapped.rendererCount).filter { mapped.getRendererType(it) == trackType }
        if (rendererIndices.isEmpty()) return
        if (selection == null) {
            rendererIndices.forEach { renderer ->
                builder.clearSelectionOverrides(renderer)
                builder.setRendererDisabled(renderer, true)
            }
            if (trackType == C.TRACK_TYPE_TEXT) lastSelectedSubtitle = null
        } else {
            // UI uses a group ordinal within a type. Walk matching renderers' groups in order.
            var ordinal = selection.groupIndex
            var renderer = -1
            var groupIndex = -1
            for (index in rendererIndices) {
                val groups = mapped.getTrackGroups(index)
                if (ordinal in 0 until groups.length) { renderer = index; groupIndex = ordinal; break }
                ordinal -= groups.length
            }
            if (renderer < 0) return
            val groups = mapped.getTrackGroups(renderer)
            if (selection.trackIndex !in 0 until groups[groupIndex].length) return
            rendererIndices.forEach { index ->
                builder.clearSelectionOverrides(index)
                builder.setRendererDisabled(index, index != renderer)
            }
            builder.setOverrideForType(TrackSelectionOverride(groups[groupIndex], selection.trackIndex))
            builder.setSelectionOverride(renderer, groups, DefaultTrackSelector.SelectionOverride(groupIndex, selection.trackIndex))
            if (trackType == C.TRACK_TYPE_TEXT) lastSelectedSubtitle = selection
        }
        trackSelector.setParameters(builder)
    }

    // ---- 进度 ----

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                speedMonitor.tick()
                val position = player.currentPosition
                val actualDuration = player.duration.takeIf { it > 0 }
                val effectiveDuration = actualDuration ?: _uiState.value.durationMs
                _uiState.update { current ->
                    current.copy(
                        positionMs = position,
                        durationMs = actualDuration ?: current.durationMs,
                        isSeekable = player.isCurrentMediaItemSeekable,
                    )
                }
                session?.let { s ->
                    _progress.tryEmit(
                        PlaybackProgress(
                            serverId = s.serverId,
                            itemId = s.itemId,
                            positionMs = position,
                            durationMs = effectiveDuration,
                            isPaused = !player.playWhenReady,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            sessionId = s.source.sessionId,
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
            durationMs = player.duration.takeIf { it > 0 } ?: _uiState.value.durationMs,
            isPaused = !player.playWhenReady,
            updatedAtEpochMs = System.currentTimeMillis(),
            sessionId = s.source.sessionId,
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
        playbackGeneration++
        subtitleOperation++
        progressJob?.cancel()
        audioSpectrumSessionActive = false
        audioSpectrumController.clear()
        val finalProgress = currentProgress()
        session = null
        _events.trySend(PlaybackEvent.Stopped)
        logger.i(LogTag.PLAYER, "播放停止 serverId=${finalProgress?.serverId} itemId=${finalProgress?.itemId}")
        return finalProgress
    }

    override fun release() {
        if (released) return
        released = true
        playbackGeneration++
        subtitleOperation++
        audioSpectrumSessionActive = false
        progressJob?.cancel()
        audioSpectrumController.release()
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
        const val EXTERNAL_SUBTITLE_CONFIRM_ATTEMPTS = 41
        const val EXTERNAL_SUBTITLE_CONFIRM_INTERVAL_MS = 50L
        const val PROGRESS_INTERVAL_MS = 1_000L

        /** 本引擎可侧挂的字幕 MIME（与 SubtitleFormats 覆盖面一致）。 */
        val SUPPORTED_SUBTITLE_MIMES = setOf(
            "application/x-subrip",
            "text/x-ssa",
            "text/vtt",
        )
    }
}

/**
 * Media3 媒体项重建的保留快照（位置/暂停/倍速）。
 * 独立 data class 便于对"重建保留纪律"做引擎层 fake 验证。
 */
data class PlaybackRestoreSnapshot(
    val positionMs: Long,
    val playWhenReady: Boolean,
    val speed: Float,
)
