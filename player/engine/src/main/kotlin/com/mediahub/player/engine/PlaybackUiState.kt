package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

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
    /** 实际正在输出的音频格式（MIME）；null=无音频输出（无声判据，Phase 1B-2.4）。 */
    val audioFormatMime: String? = null,
    val selectedSubtitle: TrackSelection? = null,
    /** 轨道行序号 → 引擎选择地址（同组多轨展开后的精确映射；见 ADR-032 勘误）。 */
    val trackRowMap: TrackRowMap = TrackRowMap.EMPTY,
    val error: PlaybackError? = null,
    val mediaTitle: String? = null,
    val isSeekable: Boolean = true,
)
