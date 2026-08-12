package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emby 媒体库相关 DTO（Phase 1B-1 浏览）。
 * 所有字段按真实 Emby JSON 命名用 @SerialName；非关键字段全部可空/带默认值，
 * 缺失字段不得导致整页解析失败（配合 ApiClient 的 ignoreUnknownKeys/coerceInputValues）。
 */

/** /Users/{userId}/Views 与 /Users/{userId}/Items 的通用查询结果包装。 */
@Serializable
data class EmbyQueryResultDto<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

/**
 * Emby BaseItem（同时用于 Views 顶层库与 Items 条目）。
 * 只解析 Phase 1B-1 浏览所需字段，不复制完整 Swagger DTO。
 */
@Serializable
data class EmbyBaseItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("UserData") val userData: EmbyUserDataDto? = null,
)

/** 用户数据（仅保留浏览所需的最小字段，Phase 1B-1 不用播放进度）。 */
@Serializable
data class EmbyUserDataDto(
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)
