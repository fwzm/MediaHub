package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyImageType

/**
 * Emby 图片 URL 解析（Phase 1B-2.3，ADR-026：Token 只走 Header，URL 仅 tag/maxWidth/quality）。
 *
 * 类型策略：
 * - MOVIE/SERIES/SEASON：posterUrl=Primary（列表宽度），backdropUrl=Backdrop[0]（详情页宽度）；
 * - EPISODE/VIDEO：posterUrl=Thumb ?? Primary（16:9 缩略图语义），无 backdrop；
 * - FOLDER/AUDIO/其他：不生成（UI 用类型图标占位）。
 * ImageTags 缺失对应类型时不发请求（避免必然 404 的 URL）。
 */
object EmbyImageMapper {

    /** 列表/卡片尺寸（服务端缩放，节省流量；Coil 端再按 composable 尺寸下采样）。 */
    const val LIST_MAX_WIDTH = 400

    /** 详情页 backdrop 宽度。 */
    const val BACKDROP_MAX_WIDTH = 1280

    fun posterUrl(api: EmbyApiClient, itemId: String, imageTags: Map<String, String>?): String? {
        val thumbTag = imageTags?.get(EmbyImageType.THUMB.wireName)
        if (thumbTag != null) {
            return api.imageUrl(itemId, EmbyImageType.THUMB, thumbTag, LIST_MAX_WIDTH)
        }
        val primaryTag = imageTags?.get(EmbyImageType.PRIMARY.wireName) ?: return null
        return api.imageUrl(itemId, EmbyImageType.PRIMARY, primaryTag, LIST_MAX_WIDTH)
    }

    fun personImageUrl(api: EmbyApiClient, personId: String, tag: String?): String? =
        api.imageUrl(personId, EmbyImageType.PRIMARY, tag, LIST_MAX_WIDTH)

    fun backdropUrl(api: EmbyApiClient, itemId: String, backdropTags: List<String>): String? =
        backdropTags.firstOrNull()
            ?.let { api.imageUrl(itemId, EmbyImageType.BACKDROP, it, BACKDROP_MAX_WIDTH) }

    /** map 后按条目类型 enrich 图片字段（mapper 保持纯函数，URL 构建统一在这里）。 */
    fun enrich(item: MediaItem, api: EmbyApiClient, imageTags: Map<String, String>?, backdropTags: List<String>): MediaItem =
        when (item.type) {
            MediaType.MOVIE, MediaType.SERIES, MediaType.SEASON -> item.copy(
                posterUrl = imageTags?.get(EmbyImageType.PRIMARY.wireName)
                    ?.let { api.imageUrl(item.id, EmbyImageType.PRIMARY, it, LIST_MAX_WIDTH) },
                backdropUrl = backdropUrl(api, item.id, backdropTags),
            )

            MediaType.EPISODE, MediaType.VIDEO -> item.copy(
                posterUrl = posterUrl(api, item.id, imageTags),
                backdropUrl = null,
            )

            else -> item
        }
}
