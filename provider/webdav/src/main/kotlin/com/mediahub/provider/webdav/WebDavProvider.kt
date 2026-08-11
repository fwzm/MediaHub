package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
import com.mediahub.model.Episode
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.base.BaseMediaServerProvider

/**
 * WebDAV Provider（Phase 0 骨架）。
 *
 * 待实现（见 TASKS.md）：
 *  - PROPFIND 列目录（Depth: 1）→ 文件树浏览
 *  - Basic/Digest 认证
 *  - 直链 = baseUrl + 路径（可带 Range），无需"临时 URL"概念
 */
class WebDavProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger) {

    override fun capabilities(): Set<ProviderCapability> =
        setOf(ProviderCapability.AUTH, ProviderCapability.BROWSE)

    override suspend fun authHeaders(): Map<String, String> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV Basic 认证头")

    override suspend fun authenticate(credentials: Credentials): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 认证")

    override suspend fun refreshSession(): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 认证刷新")

    override suspend fun currentUser(): MediaUser? = null

    override suspend fun getLibraries(): List<MediaLibrary> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 根目录视图")

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 条目浏览")

    override suspend fun getSeasons(seriesId: String): List<Season> = emptyList()

    override suspend fun getEpisodes(seasonId: String): List<Episode> = emptyList()

    override suspend fun getItemDetail(itemId: String): MediaDetail =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 详情")

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV PROPFIND 列目录")

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 播放源解析")

    override suspend fun reportProgress(progress: PlaybackProgress) = Unit

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 搜索")

    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> = emptyList()

    override suspend fun getContinueWatching(limit: Int): List<MediaItem> = emptyList()

    override suspend fun getResumePosition(itemId: String): Long? = null
}
