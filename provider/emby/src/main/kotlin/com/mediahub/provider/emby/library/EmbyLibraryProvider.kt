package com.mediahub.provider.emby.library

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
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
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyLibraryMapper
import com.mediahub.provider.emby.mapper.EmbyMediaItemMapper
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
) : MediaLibraryProvider {

    override suspend fun getLibraries(): List<MediaLibrary> {
        val (token, userId) = requireSession()
        return try {
            val views = api.getUserViews(token, userId)
            views.items.mapNotNull { EmbyLibraryMapper.mapLibrary(it, server.id) }
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
        val (token, userId) = requireSession()
        // libraryId 即当前容器 id（顶层 view id，或进入后的 series/season/folder id）
        return try {
            val result = api.getUserItems(token, userId, parentId = libraryId, page = page)
            PagedResult(
                items = result.items.map { EmbyMediaItemMapper.map(it, server.id) },
                totalCount = result.totalRecordCount,
                hasMore = (page.offset + result.items.size) < result.totalRecordCount,
                nextOffset = if ((page.offset + result.items.size) < result.totalRecordCount) {
                    page.offset + result.items.size
                } else {
                    null
                },
            )
        } catch (e: Exception) {
            throw mapError(e)
        }
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
