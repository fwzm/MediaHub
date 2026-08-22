package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emby 条目详情与播放信息 DTO（Phase 1B-2，最小字段集）。
 *
 * 只解析详情/播放必需字段，不复制完整 Swagger DTO；缺失字段带默认值，
 * 不得导致整体解析失败（配合 ApiClient 的 ignoreUnknownKeys/coerceInputValues）。
 */
/** 条目详情：GET /Users/{userId}/Items/{itemId} 响应（BaseItemDto 详情版）。 */
@Serializable
data class EmbyUserItemDto(
    @SerialName("Id") override val id: String? = null,
    @SerialName("Name") override val name: String? = null,
    @SerialName("Type") override val type: String? = null,
    @SerialName("MediaType") override val mediaType: String? = null,
    @SerialName("IsFolder") override val isFolder: Boolean = false,
    @SerialName("ParentId") override val parentId: String? = null,
    @SerialName("SeriesId") override val seriesId: String? = null,
    @SerialName("SeasonId") override val seasonId: String? = null,
    @SerialName("IndexNumber") override val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") override val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") override val productionYear: Int? = null,
    @SerialName("RunTimeTicks") override val runTimeTicks: Long? = null,
    @SerialName("Overview") override val overview: String? = null,
    @SerialName("Genres") override val genres: List<String> = emptyList(),
    @SerialName("Container") override val container: String? = null,
    @SerialName("CommunityRating") override val communityRating: Double? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("UserData") override val userData: EmbyUserDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSourceInfoDto> = emptyList(),
    @SerialName("MediaStreams") val mediaStreams: List<EmbyMediaStreamDto> = emptyList(),
    @SerialName("Chapters") val chapters: List<EmbyChapterInfoDto> = emptyList(),
) : EmbyItemFields

/** 播放信息：POST /Items/{itemId}/PlaybackInfo 响应。 */
@Serializable
data class EmbyPlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSourceInfoDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)
/**
 * PlaybackInfo 请求（官方 POST PlaybackInfo + PlaybackInfoRequest body）。
 * Phase 1B-2.1：协议协商改用官方 POST 形式，GET 上未经 contract 保证的参数全部移入 body。
 */
@Serializable
data class EmbyPlaybackInfoRequestDto(
    @SerialName("UserId") val userId: String,
    @SerialName("IsPlayback") val isPlayback: Boolean = true,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = false,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = false,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long? = null,
    @SerialName("DeviceProfile") val deviceProfile: EmbyDeviceProfileDto = EmbyDeviceProfileDto(),
)
/**
 * 最小官方 DeviceProfile：声明当前客户端接受视频文件流，不复制完整 Swagger。
 * Direct/Transcode 开关属于 PlaybackInfoRequest，禁止在 DeviceProfile 中重复伪造同名字段。
 */
@Serializable
data class EmbyDeviceProfileDto(
    @SerialName("Name") val name: String = "MediaHub",
    @SerialName("SupportedMediaTypes") val supportedMediaTypes: String = "Video",
    @SerialName("DirectPlayProfiles")
    val directPlayProfiles: List<EmbyDirectPlayProfileDto> = listOf(EmbyDirectPlayProfileDto()),
)

/** 广泛的视频文件流能力；实际解码失败仍由 Media3 结构化上报，不回退服务器转码。 */
@Serializable
data class EmbyDirectPlayProfileDto(
    @SerialName("Type") val type: String = "Video",
)
/** 单个 MediaSource（同一媒体的多个版本/容器）。 */
@Serializable
data class EmbyMediaSourceInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    /** 服务端文件路径（用于识别 .iso 蓝光镜像等不可直接流式播放的源）。 */
    @SerialName("Path") val path: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<EmbyMediaStreamDto> = emptyList(),
    /** 播放源级请求头（官方 MediaSourceInfo.RequiredHttpHeaders），必须并入播放请求。 */
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String> = emptyMap(),
    /** 服务端提供的 Direct Stream 地址（本实现自行拼 URL，此字段仅作参考保留）。 */
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
)
/** 媒体流（视频/音频/字幕）。 */
@Serializable
data class EmbyMediaStreamDto(
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("SampleRate") val sampleRate: Int? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Level") val level: Int? = null, // Emby 返回整数（如 153=HEVC 5.1）；旧版误声明 String 导致真实响应解析失败
    @SerialName("PixelFormat") val pixelFormat: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("ExtendedVideoType") val extendedVideoType: String? = null,
    @SerialName("ExtendedVideoSubType") val extendedVideoSubType: String? = null,
)
/** 章节信息（章节跳转用）。 */
@Serializable
data class EmbyChapterInfoDto(
    @SerialName("StartPositionTicks") val startPositionTicks: Long? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ChapterIndex") val chapterIndex: Int? = null,
)
