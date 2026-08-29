package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin 媒体库相关 DTO（Phase 1G-B）。独立实现，禁止 import provider:emby DTO
 * （ADR-039：JSON shape 相似也不建立 "Jellyfin == Emby" 依赖）。
 * 字段按真实 Jellyfin BaseItemDto 协议命名；缺失字段不得导致整页解析失败。
 */

/** /Users/{userId}/Views 与 /Users/{userId}/Items 的通用查询结果包装。 */
@Serializable
data class JellyfinQueryResultDto<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

/** Jellyfin 用户数据（播放进度/收藏）。 */
@Serializable
data class JellyfinUserDataDto(
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

/** Jellyfin 演职人员条目。 */
@Serializable
data class JellyfinPersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class JellyfinStudioDto(
    @SerialName("Name") val name: String? = null,
)

/**
 * Jellyfin BaseItemDto（列表与详情共用一份：详情为单条目全量端点，列表按 Fields 裁剪）。
 * 只解析浏览/详情所需字段，不复制完整 Swagger DTO。
 */
@Serializable
data class JellyfinItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("SortName") val sortName: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("UserData") val userData: JellyfinUserDataDto? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String>? = null,
    @SerialName("People") val people: List<JellyfinPersonDto> = emptyList(),
    @SerialName("Studios") val studios: List<JellyfinStudioDto> = emptyList(),
)
