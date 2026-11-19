package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/** Evidence from the audio output API, independent of the encoded input codec. */
enum class AudioOutputStatus { UNKNOWN, ACTIVE, UNAVAILABLE }

/**
 * 引擎侧真实观测到的格式 / 解码器 / 渲染器，供播放信息面板显示「运行观测」段。
 *
 * 与用户偏好、源声明严格分开：这里每个字段都必须有引擎回调或 UI 结构事实作依据，
 * 未观测到保持 null —— 面板回落为「—」，绝不把配置或源参数写成观测值。
 */
data class PlaybackObservedInfo(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    /** 容器/封装（Media3 Format.containerMimeType 或 mpv file-format）。 */
    val container: String? = null,
    val videoBitrateBps: Long? = null,
    val audioBitrateBps: Long? = null,
    val frameRate: Float? = null,
    /** Media3 解码器名（onVideoDecoderInitialized / onAudioDecoderInitialized 回调）。 */
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** mpv 的 hwdec-current（如 mediacodec/no）；Media3 无等价观测，保持 null。 */
    val hwdec: String? = null,
    /** 画面承载方式：本工程两种引擎都由 UI 的 AndroidView + SurfaceView 承载。 */
    val renderer: String? = null,
) {
    /**
     * Media3 Format → 视频观测。编码串优先、空白回退 MIME；码率/帧率非正值视为未知
     * （Media3 用 Format.NO_VALUE=-1 表示未知）。未观测到的新值一律不覆盖已有观测。
     */
    fun withVideoFormat(
        codecs: String?,
        mimeType: String?,
        bitrate: Int,
        frameRate: Float,
    ): PlaybackObservedInfo = copy(
        videoCodec = codecs?.takeIf { it.isNotBlank() } ?: mimeType?.takeIf { it.isNotBlank() } ?: videoCodec,
        videoBitrateBps = bitrate.takeIf { it > 0 }?.toLong() ?: videoBitrateBps,
        frameRate = frameRate.takeIf { it > 0f } ?: this.frameRate,
    )

    fun withAudioFormat(codecs: String?, mimeType: String?, bitrate: Int): PlaybackObservedInfo = copy(
        audioCodec = codecs?.takeIf { it.isNotBlank() } ?: mimeType?.takeIf { it.isNotBlank() } ?: audioCodec,
        audioBitrateBps = bitrate.takeIf { it > 0 }?.toLong() ?: audioBitrateBps,
    )

    fun withContainer(name: String?): PlaybackObservedInfo =
        copy(container = name?.takeIf { it.isNotBlank() } ?: container)

    fun withVideoDecoder(name: String?): PlaybackObservedInfo =
        copy(videoDecoder = name?.takeIf { it.isNotBlank() } ?: videoDecoder)

    fun withAudioDecoder(name: String?): PlaybackObservedInfo =
        copy(audioDecoder = name?.takeIf { it.isNotBlank() } ?: audioDecoder)

    fun withHwdec(name: String?): PlaybackObservedInfo =
        copy(hwdec = name?.takeIf { it.isNotBlank() } ?: hwdec)

    fun withRenderer(name: String?): PlaybackObservedInfo =
        copy(renderer = name?.takeIf { it.isNotBlank() } ?: renderer)

    fun withVideoCodec(name: String?): PlaybackObservedInfo =
        copy(videoCodec = name?.takeIf { it.isNotBlank() } ?: videoCodec)

    fun withAudioCodec(name: String?): PlaybackObservedInfo =
        copy(audioCodec = name?.takeIf { it.isNotBlank() } ?: audioCodec)

    fun withVideoBitrate(bps: Double?): PlaybackObservedInfo =
        copy(videoBitrateBps = bps?.takeIf { it > 0 }?.toLong() ?: videoBitrateBps)

    fun withAudioBitrate(bps: Double?): PlaybackObservedInfo =
        copy(audioBitrateBps = bps?.takeIf { it > 0 }?.toLong() ?: audioBitrateBps)

    fun withFrameRate(fps: Double?): PlaybackObservedInfo =
        copy(frameRate = fps?.takeIf { it > 0 }?.toFloat() ?: frameRate)

    companion object {
        /** UI 侧 AndroidView + SurfaceView 承载画面；两个引擎共用同一承载方式。 */
        const val SURFACE_VIEW_RENDERER: String = "SurfaceView"
    }
}

/** 播放器 UI 状态（Compose 直接收集）。 */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudio: TrackSelection? = null,
    /** Engine-reported format: Media3 input MIME or mpv output samples. Null means unknown. */
    val audioFormatMime: String? = null,
    val audioOutputStatus: AudioOutputStatus = AudioOutputStatus.UNKNOWN,
    val selectedSubtitle: TrackSelection? = null,
    val error: PlaybackError? = null,
    val mediaTitle: String? = null,
    val isSeekable: Boolean = true,
    /** 引擎侧真实观测（null 字段=未观测）；与偏好、源声明严格分开。 */
    val observed: PlaybackObservedInfo = PlaybackObservedInfo(),
    /**
     * **用户播放意图**（G3）。
     *
     * 与 [isPlaying]（实际是否在出帧/出声）是两个不同的概念：
     * Media3 在缓冲中、音频焦点被短暂抑制、surface 未就绪时会令 isPlaying=false，
     * 而此时 playWhenReady 仍为 true —— 用户并未暂停。
     *
     * 因此**播放/暂停按钮的图标与语义必须跟随本字段**，否则缓冲时图标会闪成「播放」，
     * 用户点它反而变成「继续播放」，看起来像暂停无效。
     *
     * 默认值取 [isPlaying]：未显式设置该字段的引擎（如旧实现）行为保持不变。
     */
    val playWhenReady: Boolean = isPlaying,
    /** Media3 row ordinals and engine group/track coordinates belong to this snapshot. */
    val trackRowMap: TrackRowMap = TrackRowMap.EMPTY,
) {
    fun shouldWarnAboutAudioOutput(): Boolean =
        audioOutputStatus == AudioOutputStatus.UNAVAILABLE && audioTracks.isNotEmpty() &&
            isPlaying && !isBuffering && !isEnded
}
