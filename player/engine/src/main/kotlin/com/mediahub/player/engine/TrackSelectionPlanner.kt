package com.mediahub.player.engine

import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * 轨道选择的纯决策层（T0003）。
 *
 * 把"选择地址 → selector 参数变更"从 [PlaybackEngine] 里抽出来，使该路径**无需真实媒体**
 * 即可断言（真实 `MappedTrackInfo` 需要准备完成的媒体源，Robolectric 下不可得）。
 *
 * 语义（见 ADR-032 勘误）：
 * - renderer 下标必须由 [MappedTrackInfo.getRendererType] 从类型求得，类型常量不是 rendererIndex；
 * - 越界 / 负值 / 陈旧快照令牌一律拒绝，不改动任何类型配置；
 * - 关闭字幕（[TrackSelection] 为 null）清掉该 renderer 上的显式 override 后禁用，避免残留 override 复活。
 */
object TrackSelectionPlanner {

    /** 选择计划：要么禁用 renderer，要么对指定组 / 轨设置 override。 */
    sealed interface Plan {
        val rendererIndex: Int

        data class Disable(override val rendererIndex: Int) : Plan

        data class Override(
            override val rendererIndex: Int,
            val groups: TrackGroupArray,
            val groupIndex: Int,
            val trackIndex: Int,
        ) : Plan
    }

    /** 承载 [trackType] 的 renderer 下标；同类型多个 renderer 时取第一个。 */
    fun rendererIndexFor(mapped: MappedTrackInfo, trackType: Int): Int? {
        for (index in 0 until mapped.rendererCount) {
            if (mapped.getRendererType(index) == trackType) return index
        }
        return null
    }

    /**
     * 计算选择计划；返回 null 表示本次选择不产生任何参数变更。
     *
     * @param currentSnapshotToken 引擎当前轨道快照令牌；[TrackSelection.snapshotToken] 与之不符则拒绝。
     */
    fun plan(
        rendererIndex: Int,
        groups: TrackGroupArray,
        selection: TrackSelection?,
        currentSnapshotToken: Long,
    ): Plan? {
        if (selection == null) return Plan.Disable(rendererIndex)

        // 陈旧快照的地址直接丢弃，不误选新快照中的同号轨道
        if (selection.snapshotToken != null && selection.snapshotToken != currentSnapshotToken) return null
        if (selection.groupIndex < 0 || selection.trackIndex < 0) return null
        if (selection.groupIndex >= groups.length) return null
        val group = groups.get(selection.groupIndex)
        if (selection.trackIndex >= group.length) return null

        return Plan.Override(rendererIndex, groups, selection.groupIndex, selection.trackIndex)
    }

    /** 把计划写入 selector 参数 builder。 */
    fun apply(
        builder: DefaultTrackSelector.Parameters.Builder,
        plan: Plan,
    ): DefaultTrackSelector.Parameters.Builder = when (plan) {
        is Plan.Disable -> {
            builder.clearSelectionOverrides(plan.rendererIndex)
            builder.setRendererDisabled(plan.rendererIndex, true)
        }

        is Plan.Override -> {
            builder.setRendererDisabled(plan.rendererIndex, false)
            builder.setSelectionOverride(
                plan.rendererIndex,
                plan.groups,
                DefaultTrackSelector.SelectionOverride(plan.groupIndex, plan.trackIndex),
            )
        }
    }
}
