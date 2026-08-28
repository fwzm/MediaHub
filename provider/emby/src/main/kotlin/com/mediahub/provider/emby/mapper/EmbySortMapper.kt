package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaSort
import com.mediahub.model.MediaSortCapabilities
import com.mediahub.model.MediaSortField
import com.mediahub.model.SortDirection

/**
 * MediaHub 排序语义 → Emby /Users/{userId}/Items 的 SortBy/SortOrder 映射
 * （Phase 1C-2）。Emby 协议命名止步于本文件，禁止泄漏到 domain / UI。
 *
 * 方向：SortOrder=Ascending/Descending。SERVER_DEFAULT 与 RANDOM 无方向语义
 * （MediaSort.hasDirection=false），映射层必须省略 SortOrder。
 *
 * RANDOM 快照语义：Emby 的 SortBy=Random 跨页不保证不重不漏（每次请求各自随机），
 * 调用方（EmbyLibraryProvider）对 offset>0 直接返回空快照页，禁止伪分页。
 */
object EmbySortMapper {

    /** Emby 支持的排序字段能力自述（UI capability-aware 的唯一来源）。 */
    val CAPABILITIES: MediaSortCapabilities = MediaSortCapabilities(
        setOf(
            MediaSortField.SERVER_DEFAULT,
            MediaSortField.DATE_ADDED,
            MediaSortField.TITLE,
            MediaSortField.COMMUNITY_RATING,
            MediaSortField.CRITIC_RATING,
            MediaSortField.PRODUCTION_YEAR,
            MediaSortField.PREMIERE_DATE,
            MediaSortField.OFFICIAL_RATING,
            MediaSortField.RUNTIME,
            MediaSortField.BITRATE,
            MediaSortField.SIZE,
            MediaSortField.RANDOM,
        ),
    )

    /** MediaSortField → Emby SortBy wire 值；SERVER_DEFAULT 无 SortBy（null = 请求不携带）。 */
    fun sortBy(field: MediaSortField): String? = when (field) {
        MediaSortField.SERVER_DEFAULT -> null
        MediaSortField.DATE_ADDED -> "DateCreated"
        MediaSortField.TITLE -> "SortName"
        MediaSortField.COMMUNITY_RATING -> "CommunityRating"
        MediaSortField.CRITIC_RATING -> "CriticRating"
        MediaSortField.PRODUCTION_YEAR -> "ProductionYear"
        MediaSortField.PREMIERE_DATE -> "PremiereDate"
        MediaSortField.OFFICIAL_RATING -> "OfficialRating"
        MediaSortField.RUNTIME -> "Runtime"
        MediaSortField.BITRATE -> "Bitrate"
        MediaSortField.SIZE -> "Size"
        MediaSortField.RANDOM -> "Random"
    }

    /** 有方向语义的排序 → Ascending/Descending；无方向语义返回 null（省略 SortOrder）。 */
    fun sortOrder(sort: MediaSort): String? {
        if (!sort.hasDirection) return null
        return when (sort.direction) {
            SortDirection.ASC -> "Ascending"
            SortDirection.DESC -> "Descending"
        }
    }
}
