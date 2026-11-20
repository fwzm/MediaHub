package com.mediahub.player.engine

/**
 * 轨道选择地址（运行时，player:engine 内部契约）。
 *
 * 明确区分四个概念（ADR-032 勘误）：
 * - **type**（音频 / 文本 / 视频）：Media3 的 `C.TRACK_TYPE_*`，**不是** renderer 下标；
 * - **rendererIndex**：`MappedTrackInfo` 中 renderer 的物理排列位置，随设备/轨道器实现变化；
 * - **groupIndex**：同类型 renderer 的组按顺序展平后的组序号；引擎换算到实际 renderer 内组号；
 * - **trackIndex**：某个 [androidx.media3.common.TrackGroup] 内单条轨的序号。
 *
 * [groupIndex] / [trackIndex] 只有在**同一次轨道快照**（同一次 `onTracksChanged`）内才有意义。
 * 旧实现把 `C.TRACK_TYPE_AUDIO` / `C.TRACK_TYPE_TEXT` 直接当作 `rendererIndex` 传给
 * `getTrackGroups` / `setRendererDisabled` / `setSelectionOverride`，类型常量不等于 renderer
 * 排列位置，因此选择被静默拒绝或命中错误 renderer。
 *
 * [snapshotToken] 是生成该地址的轨道快照标识；[PlaybackEngine] 在应用前校验它，旧快照的
 * 行回调不会误选新快照中的同号轨道。null 表示调用方不绑定快照（测试与兼容路径）。
 */
/**
 * 轨道选择请求的真实结果（#80）。引擎不得静默丢弃用户选择：
 * UI 依据本结果提示“列表已更新/轨道不可用”，而不是假装切换成功。
 */
enum class TrackSelectionResult {
    /** 选择已下发并生效。 */
    APPLIED,

    /** 行来自旧快照（或引擎已替换），请求未下发。 */
    STALE,

    /** 引擎尚未发布可用的轨道映射（例如尚未 onTracksChanged）。 */
    TRACKS_UNAVAILABLE,

    /** 该轨道类型没有可用 renderer。 */
    NO_RENDERER,

    /** 组序号/轨序号在映射中不存在。 */
    TRACK_UNAVAILABLE,

    /** 引擎尚未就绪（无当前播放实例）。 */
    ENGINE_NOT_READY,
}

data class TrackSelection(
    val groupIndex: Int,
    val trackIndex: Int,
    val snapshotToken: Long? = null,
)
