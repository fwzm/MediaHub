package com.mediahub.provider.emby.library

import com.mediahub.core.logging.Logger
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
import com.mediahub.provider.emby.EmbyProviderSupport
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyImageType
import com.mediahub.provider.emby.api.EmbyQueryResultDto
import com.mediahub.provider.emby.api.EmbyBaseItemDto
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import com.mediahub.provider.emby.mapper.EmbyLibraryMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
import com.mediahub.provider.emby.mapper.EmbySortMapper
import com.mediahub.provider.emby.session.EmbySessionStore

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
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
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
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        // libraryId 即当前容器 id（顶层 view id，或进入后的 series/season/folder id）
        return try {
            val result = api.getUserItems(token, userId, parentId = libraryId, page = page)
            toPagedResult(result, page)
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
        }
    }

    // ---- Phase 1C-2：排序下沉（Query Pipeline）；1D 起同 capability 覆盖筛选 ----

    override val capabilities = EmbySortMapper.CAPABILITIES

    /**
     * 带 MediaListQuery 的浏览：SortBy/SortOrder 传给服务器，在分页之前执行；
     * 红线——禁止拿到分页结果后再本地 sortedBy（只会排当前页，全库排序语义错误）。
     *
     * RANDOM 快照语义：Emby 的 SortBy=Random 跨页各自随机（不重不漏无保证），
     * 只承诺 offset=0 的单次随机快照；offset>0 返回空页（hasMore=false），
     * 调用方不得对 RANDOM 结果继续翻页。
     */
    override suspend fun getItems(libraryId: String, query: MediaListQuery): PagedResult<MediaItem> {
        val isRandomSnapshot = query.sort.field == MediaSortField.RANDOM
        if (isRandomSnapshot && query.page.offset > 0) {
            return PagedResult(items = emptyList(), totalCount = null, hasMore = false, nextOffset = null)
        }
        val (token, userId) = EmbyProviderSupport.requireSession(server, tokenStore, sessionStore)
        return try {
            val result = api.getUserItems(
                token,
                userId,
                parentId = libraryId,
                page = query.page,
                sortBy = EmbySortMapper.sortBy(query.sort.field),
                sortOrder = EmbySortMapper.sortOrder(query.sort),
                // Phase 1D：筛选与排序同一请求下沉服务器；container-scoped（导航栈负责 reset/restore）
                filter = query.filter,
            )
            val page = toPagedResult(result, query.page)
            // 快照语义（Integration 审计 §4.4）：即使服务器 TotalRecordCount 更大，
            // RANDOM 也只承诺单页，hasMore/nextOffset 必须终止，禁止伪分页。
            if (isRandomSnapshot) page.copy(hasMore = false, nextOffset = null) else page
        } catch (e: Exception) {
            throw EmbyProviderSupport.mapError(server.id, e)
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

    // Phase 1B-1 不实现专用季/集接口（浏览统一走 getItems(ParentId)）
    override suspend fun getSeasons(seriesId: String): List<Season> =
        throw ProviderException.NotYetImplemented(server.id, "Emby 专用季列表")

    override suspend fun getEpisodes(seasonId: String): List<Episode> =
        throw ProviderException.NotYetImplemented(server.id, "Emby 专用集列表")
}
