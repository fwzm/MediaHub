package com.mediahub.player.engine

/**
 * 轨道选择（组 + 轨）。
 *
 * [groupIndex] 是**当前 Tracks 快照中仅按目标类型过滤后的组序号**（0..N-1，保留
 * unsupported 组），与 [TrackMapper] 产出的 [com.mediahub.model.AudioTrack.index] /
 * [com.mediahub.model.SubtitleTrack.index] 同源同序；引擎按该序号解析真实
 * `androidx.media3.common.TrackGroup` 并写入类型级 override（ADR-041）。
 * 它不是 `MappedTrackInfo.getTrackGroups(...)` 的参数语义——后者要的是 renderer 索引。
 *
 * [trackIndex] 是组内轨道序号（0..group.length-1，越界时引擎保持参数原样）。
 */
data class TrackSelection(
    val groupIndex: Int,
    val trackIndex: Int,
)
