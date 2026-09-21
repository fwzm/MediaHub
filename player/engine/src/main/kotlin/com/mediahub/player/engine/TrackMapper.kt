package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/** Media3 轨道信息 → 领域模型。 */
data class MappedTracks(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudio: TrackSelection? = null,
    val selectedSubtitle: TrackSelection? = null,
)

/**
 * 轨道映射（Phase 1B-2.4 重写；index 契约在 T0002/ADR-041 纠正）。
 *
 * **index 语义统一**：[AudioTrack.index] / [SubtitleTrack.index] / [MappedTracks.selectedAudio]
 * 全部使用"同类型内序号"（0..N-1），即**本条 [Tracks] 快照中仅按目标类型过滤后的组序号**
 * （保留 unsupported 组，不按支持状态过滤）。播放器 UI 列表位置、本映射的序号与
 * [PlaybackEngine] 选轨解析出的 TrackGroup 三者同源同序。
 * 旧实现保存的 Tracks.groups 全局序号在存在视频组时会错位
 * （全局 1 == 音频 0），导致选错组 / 选择被静默拒绝 / 选中态错乱。
 *
 * 注意：该序号不是 `MappedTrackInfo.getTrackGroups(...)` 的参数语义——那一个参数是
 * **renderer 索引**而不是轨道类型（ADR-032 原文此处描述有误，已由 ADR-041 纠正）；
 * 引擎侧已不再使用 renderer 索引，选择统一按真实 [androidx.media3.common.TrackGroup]
 * 构造类型级 override。
 */
object TrackMapper {

    fun mapTracks(tracks: Tracks): MappedTracks {
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        var selectedAudio: TrackSelection? = null
        var selectedSubtitle: TrackSelection? = null

        tracks.groups.forEach { group ->
            val format = group.getTrackFormat(0)
            val supported = group.isTrackSupported(0)
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val index = audio.size
                    audio += AudioTrack(
                        index = index,
                        language = format.language,
                        title = format.label ?: format.id,
                        codec = format.sampleMimeType,
                        channels = format.channelCount,
                        sampleRate = format.sampleRate,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        isSelected = group.isSelected,
                        isSupported = supported,
                        decoderName = runCatching {
                            format.sampleMimeType?.let { mime ->
                                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                                MediaCodecUtil.getDecoderInfo(mime, false, false)?.name
                            }
                        }.getOrNull(),
                    )
                    if (group.isSelected) selectedAudio = TrackSelection(index, 0)
                }

                C.TRACK_TYPE_TEXT -> {
                    val index = subtitles.size
                    subtitles += SubtitleTrack(
                        index = index,
                        language = format.language,
                        title = format.label,
                        format = format.sampleMimeType,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        isSelected = group.isSelected,
                        isSupported = supported,
                    )
                    if (group.isSelected) selectedSubtitle = TrackSelection(index, 0)
                }
            }
        }
        return MappedTracks(audio, subtitles, selectedAudio, selectedSubtitle)
    }
}
