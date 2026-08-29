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
 * Emby 条目 DTO 公共字段（Phase 1B-2 起 [EmbyBaseItemDto] 与 [EmbyUserItemDto] 共享），
 * 让 EmbyMediaItemMapper 只写一份映射逻辑，禁止两份 DTO 各写一套映射。
 *
 * Phase 1C-2 起新增排序/发现字段（DateCreated/CriticRating/PremiereDate/
 * OfficialRating/Size/Bitrate/SortName），由请求 Fields= 显式开启。
 */
interface EmbyItemFields {
    val id: String?
    val name: String?
    val type: String?
    val mediaType: String?
    val isFolder: Boolean
    val parentId: String?
    val seriesId: String?
    val seasonId: String?
    val indexNumber: Int?
    val parentIndexNumber: Int?
    val productionYear: Int?
    val runTimeTicks: Long?
    val overview: String?
    val genres: List<String>
    val container: String?
    val communityRating: Double?
    val userData: EmbyUserDataDto?

    // ---- Phase 1C-2 排序/发现字段（服务器按 Fields= 返回，缺失可空） ----
    val sortName: String?
    val dateCreated: String?
    val criticRating: Double?
    val premiereDate: String?
    val officialRating: String?
    val size: Long?
    val bitrate: Long?

    // ---- Phase 1E 跨源身份：ProviderIds 字典（wire 形如 {"Imdb":"tt...","Tmdb":"123"}；
    //      键名大小写不敏感、冲突 provider 丢弃——归一化见 EmbyMediaItemMapper） ----
    val providerIds: Map<String, String>?
}
/**
 * Emby BaseItem（同时用于 Views 顶层库与 Items 条目）。
 * 只解析浏览所需字段，不复制完整 Swagger DTO。
 */
@Serializable
data class EmbyBaseItemDto(
    @SerialName("Id") override val id: String? = null,
    @SerialName("Name") override val name: String? = null,
    @SerialName("Type") override val type: String? = null,
    @SerialName("MediaType") override val mediaType: String? = null,
    @SerialName("IsFolder") override val isFolder: Boolean = false,
    @SerialName("CollectionType") val collectionType: String? = null,
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
    // ---- Phase 1C-2 排序/发现字段 ----
    @SerialName("SortName") override val sortName: String? = null,
    @SerialName("DateCreated") override val dateCreated: String? = null,
    @SerialName("CriticRating") override val criticRating: Double? = null,
    @SerialName("PremiereDate") override val premiereDate: String? = null,
    @SerialName("OfficialRating") override val officialRating: String? = null,
    @SerialName("Size") override val size: Long? = null,
    @SerialName("Bitrate") override val bitrate: Long? = null,
    @SerialName("ProviderIds") override val providerIds: Map<String, String>? = null,
) : EmbyItemFields

/** 用户数据（仅保留浏览所需的最小字段，Phase 1B-1 不用播放进度）。 */
@Serializable
data class EmbyUserDataDto(
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)
