package com.mediahub.player.engine

import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/**
 * 轨道行 → 引擎选择地址的运行时映射（player:engine 内部，见 ADR-032 勘误）。
 *
 * UI 列表位置（[AudioTrack.index] / [SubtitleTrack.index]，0..N-1 的**行序号**）与引擎需要的
 * **组 / 轨地址**不是同一个值：同组多轨展开后，第 2 条字幕的行序号是 1，其引擎地址却是
 * `groupIndex=0, trackIndex=1`。本映射由 [TrackMapper] 在展开轨道时同步产出，UI 只需按行序号查询。
 *
 * 该结构只存在于 player:engine，不进入 `core:model`，避免把播放期内部地址扩散到领域模型与 Provider。
 */
data class TrackRowMap(
    val audio: Map<Int, TrackSelection> = emptyMap(),
    val subtitle: Map<Int, TrackSelection> = emptyMap(),
    /** 生成本映射的轨道快照令牌；与 [TrackSelection.snapshotToken] 一致。 */
    val snapshotToken: Long = 0L,
) {
    fun audioFor(rowIndex: Int): TrackSelection? = audio[rowIndex]

    fun subtitleFor(rowIndex: Int): TrackSelection? = subtitle[rowIndex]

    /** 所有地址是否都归属于 [token] 对应的快照（旧快照的映射已失效）。 */
    fun matches(token: Long): Boolean = snapshotToken == token

    companion object {
        /** 空映射（无轨道 / 未就绪）。 */
        val EMPTY = TrackRowMap()
    }
}
