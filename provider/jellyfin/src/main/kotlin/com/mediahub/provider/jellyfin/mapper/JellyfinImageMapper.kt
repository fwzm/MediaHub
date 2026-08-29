package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinImageType

/**
 * Jellyfin 图片 URL 解析（Phase 1G-B，ADR-039：**URL 永不含 Token/api_key**，
 * 认证由 app 层 ProviderImageAuthContributor 走标准 Authorization Header 注入）。
 *
 * 类型策略与 EmbyImageMapper 对齐（同一领域/UI 语义）：
 * - MOVIE/SERIES/SEASON：posterUrl=Primary（列表宽度），backdropUrl=Backdrop[0]（详情宽度）；
 * - EPISODE/VIDEO：posterUrl=Thumb ?? Primary（16:9 缩略图语义），无 backdrop；
 * - FOLDER/AUDIO/其他：不生成（UI 用类型图标占位）。
 */
object JellyfinImageMapper {

    /** 列表/卡片尺寸（服务端缩放）。 */
    const val LIST_MAX_WIDTH = 400

    /** 详情页 backdrop 宽度。 */
    const val BACKDROP_MAX_WIDTH = 1280

    /** map 后按条目类型 enrich 图片字段（mapper 保持纯函数，URL 构建统一在这里）。 */
    fun enrich(item: MediaItem, api: JellyfinApiClient, imageTags: Map<String, String>?, backdropTags: List<String>): MediaItem =
        when (item.type) {
            MediaType.MOVIE, MediaType.SERIES, MediaType.SEASON -> item.copy(
                posterUrl = imageTags?.get(JellyfinImageType.PRIMARY.wireName)
                    ?.let { api.imageUrl(item.id, JellyfinImageType.PRIMARY, it, LIST_MAX_WIDTH) },
                backdropUrl = backdropTags.firstOrNull()
                    ?.let { api.imageUrl(item.id, JellyfinImageType.BACKDROP, it, BACKDROP_MAX_WIDTH) },
            )

            MediaType.EPISODE, MediaType.VIDEO -> item.copy(
                posterUrl = posterUrl(api, item.id, imageTags),
                backdropUrl = null,
            )

            else -> item
        }

    private fun posterUrl(api: JellyfinApiClient, itemId: String, imageTags: Map<String, String>?): String? {
        val thumbTag = imageTags?.get(JellyfinImageType.THUMB.wireName)
        if (thumbTag != null) {
            return api.imageUrl(itemId, JellyfinImageType.THUMB, thumbTag, LIST_MAX_WIDTH)
        }
        val primaryTag = imageTags?.get(JellyfinImageType.PRIMARY.wireName) ?: return null
        return api.imageUrl(itemId, JellyfinImageType.PRIMARY, primaryTag, LIST_MAX_WIDTH)
    }

    fun personImageUrl(api: JellyfinApiClient, personId: String, tag: String?): String? =
        tag?.let { api.imageUrl(personId, JellyfinImageType.PRIMARY, it, LIST_MAX_WIDTH) }
}
