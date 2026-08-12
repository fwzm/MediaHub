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
    @SerialName("UserData") override val userData: EmbyUserDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSourceInfoDto> = emptyList(),
    @SerialName("MediaStreams") val mediaStreams: List<EmbyMediaStreamDto> = emptyList(),
    @SerialName("Chapters") val chapters: List<EmbyChapterInfoDto> = emptyList(),
) : EmbyItemFields

/** 播放信息：GET /Items/{itemId}/PlaybackInfo 响应。 */
@Serializable
data class EmbyPlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSourceInfoDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
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
    @SerialName("MediaStreams") val mediaStreams: List<EmbyMediaStreamDto> = emptyList(),
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
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Level") val level: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
)

/** 章节信息（章节跳转用）。 */
@Serializable
data class EmbyChapterInfoDto(
    @SerialName("StartPositionTicks") val startPositionTicks: Long? = null,
    @SerialName("Name") val name: String? = null,
)
