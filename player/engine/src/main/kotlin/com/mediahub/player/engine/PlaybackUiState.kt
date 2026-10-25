package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/** Evidence from the audio output API, independent of the encoded input codec. */
enum class AudioOutputStatus { UNKNOWN, ACTIVE, UNAVAILABLE }

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
