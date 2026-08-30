package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emby 进度上报 DTO（Phase 1H，Emby PROGRESS closeout；ADR-039 review hardening 同款纪律）。
 *
 * 协议证据（1H 取证）：
 * - 三端点 body 全部字段可选（Jellyfin openapi 同源 schema `required: []`——Jellyfin fork 自
 *   Emby 3.x，其仓库至今保留 `Emby.Server.Implementations/Session/SessionManager.cs` 路径）；
 *   官方 Kodi Emby 插件（对接现役 Emby 4.x）以同一 SessionInfo JSON 投递三端点。
 * - 只携带 MediaHub 有真实数据来源的字段：
 *   ItemId（PlaybackProgress.itemId）/ PositionTicks（positionMs×10_000）/
 *   IsPaused（仅 Progress）/ PlayMethod（PlaybackProgress.mode 映射，无则省略）。
 *   禁止伪造：PlaySessionId / MediaSourceId（服务端缺省会归一化为 ItemId）/
 *   CanSeek / IsMuted / VolumeLevel（无数据来源）/ SessionId（由认证上下文绑定）。
 * - **PositionTicks 恒发（非空）**：Stopped 缺 PositionTicks 时服务端按"播放完成"处理
 *   （PlayCount++ / Played=true / 位置清零，Jellyfin 同源 SessionManager.OnPlaybackStopped
 *   实证）——退出刚打开的条目会被误标已看；负值会触发 400。故 positionMs 钳 ≥0 后恒发，
 *   0 位置发显式 0（官方 Kodi 客户端同款：起始即发 0）。
 */

/** POST /Sessions/Playing（PlaybackStartInfo，[FromBody] JSON）。 */
@Serializable
data class EmbyPlaybackStartInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlayMethod") val playMethod: String? = null,
)

/** POST /Sessions/Playing/Progress（PlaybackProgressInfo，[FromBody] JSON）。 */
@Serializable
data class EmbyPlaybackProgressInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
)

/** POST /Sessions/Playing/Stopped（PlaybackStopInfo）——退出时权威进度写入者。 */
@Serializable
data class EmbyPlaybackStopInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
)
