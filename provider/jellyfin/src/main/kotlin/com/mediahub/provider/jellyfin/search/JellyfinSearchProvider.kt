package com.mediahub.provider.jellyfin.search

import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.mapper.JellyfinImageMapper
import com.mediahub.provider.jellyfin.mapper.JellyfinItemMapper
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore

/**
 * Jellyfin 全局搜索（Phase 1G-B）：GET /Users/{userId}/Items?SearchTerm=…&Recursive=true。
 *
 * - **Recursive=true 仅用于搜索**（ADR-039：浏览红线不适用于全库搜索语义）；
 *   IncludeItemTypes 锁定 Movie,Series,Episode,Video 四类；
 * - Fields 含 ProviderIds（跨源身份必备）+ UserData；排序不传 SortBy（服务器 relevance
 *   即首版权威序，与 Emby 搜索语义一致）；
 * - 搜索命中 → MediaItem.externalIds → 现有 CanonicalIdentityGraph/SearchAggregator
 *   跨 Provider 聚合（1G-C 架构验收核心，本类零特殊分支）；
 * - 空白 query 短路：不发网络请求（首帧输入 "" 不触发无意义请求）。
 */
class JellyfinSearchProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    private val logger: Logger,
) : MediaSearchProvider {

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> {
        val searchTerm = query.trim()
        if (searchTerm.isEmpty()) {
            return PagedResult(items = emptyList(), totalCount = 0, hasMore = false, nextOffset = null)
        }
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getUserItems(
                token,
                userId,
                page = page,
                includeItemTypes = "Movie,Series,Episode,Video",
                searchTerm = searchTerm,
                recursive = true,
                sortBy = null,
            )
            PagedResult(
                items = result.items.mapNotNull { dto ->
                    JellyfinItemMapper.map(dto, server.id)?.let { item ->
                        JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
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
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }
}
