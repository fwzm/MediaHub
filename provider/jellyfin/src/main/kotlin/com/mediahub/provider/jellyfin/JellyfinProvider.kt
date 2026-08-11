package com.mediahub.provider.jellyfin

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
 * Jellyfin Provider（Phase 0 骨架）。
 *
 * 独立 Connector（与 Emby 共享 [BaseMediaServerProvider]，但协议差异各自实现）。
 * 待实现（见 TASKS.md）：
 *  - /Users/AuthenticateByName 登录（X-Emby-Authorization 头）
 *  - /Users/{userId}/Views、/Users/{userId}/Items
 *  - /Items/{itemId}/PlaybackInfo
 *  - /Sessions/Playing 进度上报
 */
class JellyfinProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger) {

    override fun capabilities(): Set<ProviderCapability> =
        setOf(ProviderCapability.AUTH, ProviderCapability.LIBRARY, ProviderCapability.SEARCH)

    override suspend fun authHeaders(): Map<String, String> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 会话鉴权头")

    override suspend fun authenticate(credentials: Credentials): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 登录（/Users/AuthenticateByName）")

    override suspend fun refreshSession(): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 会话刷新")

    override suspend fun currentUser(): MediaUser? =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 当前用户")

    override suspend fun getLibraries(): List<MediaLibrary> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 媒体库（/Users/{userId}/Views）")

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 条目浏览（/Users/{userId}/Items）")

    override suspend fun getSeasons(seriesId: String): List<Season> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 季列表")

    override suspend fun getEpisodes(seasonId: String): List<Episode> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 剧集列表")

    override suspend fun getItemDetail(itemId: String): MediaDetail =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 详情")

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 文件树浏览")

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 播放源解析（/Items/{itemId}/PlaybackInfo）")

    override suspend fun reportProgress(progress: PlaybackProgress) =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 进度上报（/Sessions/Playing）")

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 搜索（/Search/Hints）")

    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 字幕列表")

    override suspend fun getContinueWatching(limit: Int): List<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 继续观看")

    override suspend fun getResumePosition(itemId: String): Long? =
        throw ProviderException.NotYetImplemented(serverId, "Jellyfin 续播位置")
}
