package com.mediahub.provider.emby.identity

import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.CanonicalKey
import com.mediahub.model.ExternalIdProvider
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.provider.api.MediaIdentityLookupProvider
import com.mediahub.provider.emby.EmbyProviderSupport
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
import com.mediahub.provider.emby.session.EmbySessionStore

/** AnyProviderIdEquals wire 前缀（官方 provider id 小写形式：`tmdb.123` / `imdb.tt…`）。 */
private val ExternalIdProvider.wireName: String
    get() = when (this) {
        ExternalIdProvider.TMDB -> "tmdb"
        ExternalIdProvider.IMDB -> "imdb"
        ExternalIdProvider.TVDB -> "tvdb"
    }

/**
 * Emby canonical identity 精确查找（Phase 1F B1，ADR-038）：
 * GET /Users/{userId}/Items?AnyProviderIdEquals=...&Recursive=true。
 *
 * - AnyProviderIdEquals（官方参数：匹配至少一个 provider ID）而非 SearchTerm——
 *   精确身份匹配，不依赖文本标题。
 * - 多 key 逗号连接（`tmdb.123,imdb.tt…`）；IncludeItemTypes 锁定 keys 的
 *   MediaType（keys 必须同类型，复用 [EmbyApiClient.includeItemTypes] 单一映射）。
 * - Fields 与搜索共用 SEARCH_FIELDS（含 ProviderIds）；LIBRARY_FIELDS 不动（ADR-037/038）。
 * - 会话/错误映射与浏览/搜索共用 [EmbyProviderSupport]（单一来源）。
 */
class EmbyIdentityLookupProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaIdentityLookupProvider {

    override suspend fun findByCanonicalKeys(
        keys: Set<CanonicalKey>,
        page: PageRequest,
    ): PagedResult<MediaItem> {
        require(keys.isNotEmpty()) { "findByCanonicalKeys 需要非空 CanonicalKey 集合" }
        val type = keys.first().type
        require(keys.all { it.type == type }) { "findByCanonicalKeys 的 keys 必须同 MediaType" }

        val anyProviderIdEquals = keys.joinToString(",") { "${it.provider.wireName}.${it.value}" }
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.findItemsByProviderIds(
                token = token,
                userId = userId,
                anyProviderIdEquals = anyProviderIdEquals,
                includeItemTypes = api.includeItemTypes(type),
                page = page,
            )
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
