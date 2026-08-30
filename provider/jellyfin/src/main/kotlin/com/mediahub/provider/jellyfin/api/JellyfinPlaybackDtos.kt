package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin 播放/进度 DTO（Phase 1G-C，ADR-039）。独立实现，禁止 import provider:emby。
 * 字段按真实 Jellyfin 协议命名；缺失字段不得导致解析失败。
 */

/**
 * POST /Items/{itemId}/PlaybackInfo 请求体（PlaybackInfoRequest）。
 * 无转码红线（与 Emby 1B-2.1 同一策略）：EnableDirectStream=true / EnableTranscoding=false，
 * 只询问服务端能否 Direct Stream，绝不申请转码会话。
 */
/**
 * PlaybackInfoRequest 精确 contract（v10.9.0 源模型核对，ADR-039 review 修正）：
 * 无 IsPlayback 字段（Emby DTO shape 猜测性复制已删除）；MaxStreamingBitrate = int?。
 */
@Serializable
data class JellyfinPlaybackInfoRequestDto(
    @SerialName("UserId") val userId: String,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = false,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = false,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = false,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int? = null,
    @SerialName("DeviceProfile") val deviceProfile: JellyfinDeviceProfileDto = JellyfinDeviceProfileDto(),
)

/** 最小官方 DeviceProfile：声明接受视频文件流；开关在 PlaybackInfoRequest 上，不在此重复。 */
@Serializable
data class JellyfinDeviceProfileDto(
    @SerialName("Name") val name: String = "MediaHub",
    @SerialName("SupportedMediaTypes") val supportedMediaTypes: String = "Video",
    @SerialName("DirectPlayProfiles")
    val directPlayProfiles: List<JellyfinDirectPlayProfileDto> = listOf(JellyfinDirectPlayProfileDto()),
)

/** 广泛的视频文件流能力；实际解码失败由 Media3 结构化上报，不回退服务器转码。 */
@Serializable
data class JellyfinDirectPlayProfileDto(
    @SerialName("Type") val type: String = "Video",
)

/** PlaybackInfo 响应（PlaybackInfoResult）。 */
@Serializable
data class JellyfinPlaybackInfoResultDto(
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSourceInfoDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class JellyfinMediaSourceInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    /** 服务端文件路径（识别 .iso 等不可直接流式播放的源）。 */
    @SerialName("Path") val path: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStreamDto> = emptyList(),
    /** 播放源级请求头（官方 MediaSourceInfo.RequiredHttpHeaders），必须并入播放请求。 */
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String> = emptyMap(),
)

@Serializable
data class JellyfinMediaStreamDto(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
)

// ---- 进度上报（/Sessions/Playing[/Progress|/Stopped]，官方 SessionsController） ----

@Serializable
data class JellyfinPlaybackStartInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("PlayMethod") val playMethod: String? = "DirectStream",
)

@Serializable
data class JellyfinPlaybackProgressInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = "DirectStream",
)

@Serializable
data class JellyfinPlaybackStopInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
)
