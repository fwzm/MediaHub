package com.mediahub.provider.jellyfin.library

import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.Episode
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.Season
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.JellyfinProviderSupport
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.mapper.JellyfinImageMapper
import com.mediahub.provider.jellyfin.mapper.JellyfinItemMapper
import com.mediahub.provider.jellyfin.mapper.JellyfinLibraryMapper
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore

/**
 * Jellyfin 媒体库浏览（Phase 1G-B）。
 *
 * - 顶层：/Users/{userId}/Views → MediaLibrary（CollectionType 协议差异止步于 mapper）。
 * - 浏览：/Users/{userId}/Items?ParentId=...（View → Series → Season → Folder 通用子级），
 *   **不携带 Recursive**（默认 false，只取直接子级——ADR-039 红线）；
 *   SortBy=SortName 保证跨页稳定顺序。
 * - 季/集：同 wire + IncludeItemTypes 锁类型（"Season"/"Episode"）+ SortBy=IndexNumber，
 *   与既有 Detail 链的 getItems(parent)+filter 语义兼容。
 *
 * 会话：每次请求从 TokenStore + JellyfinSessionStore 读取 accessToken/userId，
 * 缺失抛 [ProviderException.AuthRequired]；错误映射走 [JellyfinProviderSupport.mapError]
 * （401→AuthExpired、取消透传）。
 */
class JellyfinLibraryProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
    private val logger: Logger,
) : MediaLibraryProvider {

    override suspend fun getLibraries(): List<MediaLibrary> {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val views = api.getUserViews(token, userId)
            views.items.mapNotNull { dto ->
                JellyfinLibraryMapper.mapLibrary(dto, server.id)?.let { library ->
                    library.copy(
                        imageUrl = dto.imageTags?.get("Primary")
                            ?.let { tag -> api.imageUrl(library.id, com.mediahub.provider.jellyfin.api.JellyfinImageType.PRIMARY, tag, JellyfinImageMapper.LIST_MAX_WIDTH) },
                    )
                }
            }
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getUserItems(token, userId, parentId = libraryId, page = page)
            toPagedResult(result, page)
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /** 专用季列表：ParentId=seriesId + IncludeItemTypes=Season + SortBy=IndexNumber。 */
    override suspend fun getSeasons(seriesId: String): List<Season> {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getUserItems(
                token, userId,
                parentId = seriesId,
                page = PageRequest(limit = 100),
                includeItemTypes = "Season",
                sortBy = "IndexNumber",
            )
            result.items.mapNotNull { dto ->
                JellyfinItemMapper.map(dto, server.id)?.let { item ->
                    JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                }?.let { item ->
                    Season(
                        serverId = server.id,
                        id = item.id,
                        seriesId = seriesId,
                        name = item.title,
                        seasonNumber = item.seasonNumber ?: 0,
                        overview = item.overview,
                        imageUrl = item.posterUrl,
                    )
                }
            }
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /** 专用集列表：ParentId=seasonId + IncludeItemTypes=Episode + SortBy=IndexNumber。 */
    override suspend fun getEpisodes(seasonId: String): List<Episode> {
        val (token, userId) = JellyfinProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getUserItems(
                token, userId,
                parentId = seasonId,
                page = PageRequest(limit = 300),
                includeItemTypes = "Episode",
                sortBy = "IndexNumber",
            )
            result.items.mapNotNull { dto ->
                JellyfinItemMapper.map(dto, server.id)?.let { item ->
                    JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                }?.let { item ->
                    Episode(
                        serverId = server.id,
                        id = item.id,
                        seriesId = item.seriesId ?: dto.seriesId.orEmpty(),
                        seasonId = seasonId,
                        name = item.title,
                        episodeNumber = item.episodeNumber ?: 0,
                        overview = item.overview,
                        imageUrl = item.posterUrl,
                        runtimeMs = item.runtimeMs,
                    )
                }
            }
        } catch (e: Exception) {
            throw JellyfinProviderSupport.mapError(server.id, e)
        }
    }

    /** 条目映射 + 服务器分页数学（与浏览/季/集共用一份）。 */
    private fun toPagedResult(
        result: com.mediahub.provider.jellyfin.api.JellyfinQueryResultDto<com.mediahub.provider.jellyfin.api.JellyfinItemDto>,
        page: PageRequest,
    ): PagedResult<MediaItem> {
        val hasMore = (page.offset + result.items.size) < result.totalRecordCount
        return PagedResult(
            items = result.items.mapNotNull { dto ->
                JellyfinItemMapper.map(dto, server.id)?.let { item ->
                    JellyfinImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                }
            },
            totalCount = result.totalRecordCount,
            hasMore = hasMore,
            nextOffset = if (hasMore) page.offset + result.items.size else null,
        )
    }
}
