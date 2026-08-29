package com.mediahub.provider.emby.search

import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.EmbyProviderSupport
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
import com.mediahub.provider.emby.session.EmbySessionStore

/**
 * Emby 全库搜索（Phase 1C-1）：GET /Users/{userId}/Items?SearchTerm=...&Recursive=true。
 *
 * - 能力声明：EMBY_PROVIDER_DESCRIPTOR 早已声明 SEARCH；本类是它的运行时落地，
 *   由 EmbyProviderFactory 填入 ProviderHandle.search。
 * - serverId 保留：映射走 [EmbyMediaItemMapper.map]（与浏览同一映射，单一来源），
 *   产出的 MediaItem.serverId 即本 server.id，聚合层据此路由播放/详情。
 * - 空白 query 短路：不发网络请求，直接空结果（首帧输入""不触发无意义请求）。
 * - 会话/错误映射与浏览共用 [EmbyProviderSupport]（单一来源）。
 */
class EmbySearchProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaSearchProvider {

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> {
        val searchTerm = query.trim()
        if (searchTerm.isEmpty()) {
            return PagedResult(items = emptyList(), totalCount = 0, hasMore = false, nextOffset = null)
        }
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.searchItems(token, userId, searchTerm = searchTerm, page = page)
            PagedResult(
                items = result.items.mapNotNull { dto ->
                    EmbyMediaItemMapper.map(dto, server.id)?.let { item ->
                        EmbyImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                    }
                },
                totalCount = result.totalRecordCount,
                hasMore = (page.offset + result.items.size) < result.totalRecordCount,
                nextOffset = if ((page.offset + result.items.size) < result.totalRecordCount) {
                    page.offset + result.items.size
                } else {
                    null
                },
            )
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }
}
