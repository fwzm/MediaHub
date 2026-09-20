package com.mediahub.feature.player

import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.TrackSelection

/**
 * 播放器轨道列表行 → 引擎选择地址（生产路径唯一转换入口）。
 *
 * UI 列表位置（[AudioTrack.index] / [SubtitleTrack.index]，0..N-1 的**行序号**）与引擎需要的
 * **组 / 轨地址**不是同一个值：同组多轨展开后，第 2 条字幕的行序号是 1，其引擎地址可能是
 * `groupIndex=0, trackIndex=1`。旧实现直接 `TrackSelection(track.index, 0)` 把行序号当组号并固定
 * 选第 0 轨，同组多轨场景下选中错轨或选择被静默拒绝。
 *
 * 地址来自引擎同步产出的 [PlaybackUiState.trackRowMap]，按行序号精确查询，不依赖 renderer 排列。
 * 查不到映射（无轨道 / 旧快照 / 越界）时返回 null，调用方放弃本次选择而不误选。
 */
object PlayerTrackSelection {

    /** 音轨行 → 选择地址；null 表示该行当前不可选（映射缺失或快照已过期）。 */
    fun addressOf(state: PlaybackUiState, track: AudioTrack): TrackSelection? =
        state.trackRowMap.audioFor(track.index)

    /**
     * 字幕行 → 选择地址。
     *
     * `null` 入参表示用户选择"关闭字幕"，沿用端口语义返回 null（引擎据null 禁用文本类型）。
     */
    fun addressOf(state: PlaybackUiState, track: SubtitleTrack?): TrackSelection? {
        if (track == null) return null
        return state.trackRowMap.subtitleFor(track.index)
    }
}
