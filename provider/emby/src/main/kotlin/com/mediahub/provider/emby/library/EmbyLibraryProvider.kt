package com.mediahub.provider.emby.library

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.TokenStore
import com.mediahub.model.Episode
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaListQuery
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaSortField
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.Season
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaQueryLibraryProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyImageType
import com.mediahub.provider.emby.api.EmbyQueryResultDto
import com.mediahub.provider.emby.api.EmbyBaseItemDto
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyLibraryMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
import com.mediahub.provider.emby.mapper.EmbySortMapper
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Emby 媒体库浏览（Phase 1B-1）。
 *
 * - 顶层：/Users/{userId}/Views → MediaLibrary。
 * - 浏览：/Users/{userId}/Items?ParentId=...（View → Series → Season → Folder 通用进入子级）。
 *
 * 会话：每次请求从 TokenStore + EmbySessionStore 读取 accessToken/userId，
 * 缺失抛 [ProviderException.AuthRequired]；401 抛 [ProviderException.AuthExpired]。
 * 不在这里复制认证生命周期/清理状态机（那是 EmbyAuthProvider 的职责）。
 */
class EmbyLibraryProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaLibraryProvider, MediaQueryLibraryProvider {

    override suspend fun getLibraries(): List<MediaLibrary> {
        val (token, userId) = requireSession()
        return try {
            val views = api.getUserViews(token, userId)
            views.items.mapNotNull { dto ->
                EmbyLibraryMapper.mapLibrary(dto, server.id)?.let { library ->
                    // 库封面（Primary）：有 ImageTags 才生成 URL
                    library.copy(
                        imageUrl = dto.imageTags?.get(EmbyImageType.PRIMARY.wireName)
                            ?.let { tag -> api.imageUrl(library.id, EmbyImageType.PRIMARY, tag, EmbyImageMapper.LIST_MAX_WIDTH) },
                    )
                }
            }
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
        val (token, userId) = requireSession()
        // libraryId 即当前容器 id（顶层 view id，或进入后的 series/season/folder id）
        return try {
            val result = api.getUserItems(token, userId, parentId = libraryId, page = page)
            toPagedResult(result, page)
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    // ---- Phase 1C-2：排序下沉（Query Pipeline） ----

    override val sortCapabilities = EmbySortMapper.CAPABILITIES

    /**
     * 带 MediaListQuery 的浏览：SortBy/SortOrder 传给服务器，在分页之前执行；
     * 红线——禁止拿到分页结果后再本地 sortedBy（只会排当前页，全库排序语义错误）。
     *
     * RANDOM 快照语义：Emby 的 SortBy=Random 跨页各自随机（不重不漏无保证），
     * 只承诺 offset=0 的单次随机快照；offset>0 返回空页（hasMore=false），
     * 调用方不得对 RANDOM 结果继续翻页。
     */
    override suspend fun getItems(libraryId: String, query: MediaListQuery): PagedResult<MediaItem> {
        if (query.sort.field == MediaSortField.RANDOM && query.page.offset > 0) {
            return PagedResult(items = emptyList(), totalCount = null, hasMore = false, nextOffset = null)
        }
        val (token, userId) = requireSession()
        return try {
            val result = api.getUserItems(
                token,
                userId,
                parentId = libraryId,
                page = query.page,
                sortBy = EmbySortMapper.sortBy(query.sort.field),
                sortOrder = EmbySortMapper.sortOrder(query.sort),
            )
            toPagedResult(result, query.page)
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    /** 条目映射 + 服务器分页数学（浏览与排序查询共用一份）。 */
    private fun toPagedResult(
        result: EmbyQueryResultDto<EmbyBaseItemDto>,
        page: PageRequest,
    ): PagedResult<MediaItem> {
        val hasMore = (page.offset + result.items.size) < result.totalRecordCount
        return PagedResult(
            items = result.items.mapNotNull { dto ->
                EmbyMediaItemMapper.map(dto, server.id)?.let { item ->
                    EmbyImageMapper.enrich(item, api, dto.imageTags, dto.backdropImageTags)
                }
            },
            totalCount = result.totalRecordCount,
            hasMore = hasMore,
            nextOffset = if (hasMore) page.offset + result.items.size else null,
        )
    }

    /** 结构化错误映射（评审 #11）：无 session、401、403、404、5xx、网络、解析分别表达。 */
    private fun mapError(e: Exception): ProviderException = when (e) {
        is ProviderException -> e
        is ApiException -> when (e.statusCode) {
            401 -> ProviderException.AuthExpired(server.id)
            404 -> ProviderException.NotFound(server.id, "媒体库或条目")
            else -> ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId)
        }

        is SerializationException -> ProviderException.Parse(server.id, e)
        is IOException -> ProviderException.Network(server.id, e)
        else -> ProviderException.Unknown(server.id, e)
    }

    private suspend fun requireSession(): Pair<String, String> {
        val token = tokenStore.readTokens(server.id)?.accessToken
            ?: throw ProviderException.AuthRequired(server.id)
        val session = sessionStore.read(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return token to session.userId
    }

    // Phase 1B-1 不实现专用季/集接口（浏览统一走 getItems(ParentId)）
    override suspend fun getSeasons(seriesId: String): List<Season> =
        throw ProviderException.NotYetImplemented(server.id, "Emby 专用季列表")

    override suspend fun getEpisodes(seasonId: String): List<Episode> =
        throw ProviderException.NotYetImplemented(server.id, "Emby 专用集列表")
}
