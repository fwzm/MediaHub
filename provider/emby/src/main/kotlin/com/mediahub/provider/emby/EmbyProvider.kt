package com.mediahub.provider.emby

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackProgressReason
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProgressProvider
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.MediaSubtitleProvider
import com.mediahub.provider.api.ProgressReportingPolicy
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Emby Connector 的 Phase 0.5 契约骨架；仅协议级公开信息探测为真实实现。 */
class EmbyProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    credentialVault: CredentialVault,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, credentialVault, logger),
    MediaAuthProvider,
    MediaLibraryProvider,
    MediaPlaybackProvider,
    MediaSearchProvider,
    MediaSubtitleProvider,
    MediaProgressProvider {

    override val descriptor: ProviderDescriptor = DESCRIPTOR
    override val reportingPolicy = ProgressReportingPolicy(periodicIntervalMs = 10_000L)

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus = connectionCheck {
        val info = apiClient.get<PublicSystemInfo>("${server.baseUrl.trimEnd('/')}/System/Info/Public")
        if (info.id.isNullOrBlank() || info.version.isNullOrBlank()) {
            throw ProviderException.Connection(serverId, "响应不是有效的 Emby System Info")
        }
        if (info.productName?.contains("Jellyfin", ignoreCase = true) == true) {
            throw ProviderException.Connection(serverId, "检测到 Jellyfin 服务，请选择 Jellyfin")
        }
        "Emby ${info.version} · ${info.serverName ?: info.id}"
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "Emby 登录（/Users/AuthenticateByName）")

    override suspend fun refreshSession(): AuthResult =
        throw ProviderException.NotYetImplemented(serverId, "Emby 会话刷新")

    override suspend fun logout() = clearCredentials()

    override suspend fun currentUser(): MediaUser? =
        throw ProviderException.NotYetImplemented(serverId, "Emby 当前用户")

    override suspend fun getLibraries(): List<MediaLibrary> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 媒体库")

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 条目浏览")

    override suspend fun getSeasons(seriesId: String): List<Season> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 季列表")

    override suspend fun getEpisodes(seasonId: String): List<com.mediahub.model.Episode> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 剧集列表")

    override suspend fun getItemDetail(itemId: String): MediaDetail =
        throw ProviderException.NotYetImplemented(serverId, "Emby 详情")

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        throw ProviderException.NotYetImplemented(serverId, "Emby 播放源解析")

    override suspend fun reportProgress(progress: PlaybackProgress, reason: PlaybackProgressReason) =
        throw ProviderException.NotYetImplemented(serverId, "Emby 进度上报")

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 搜索")

    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 字幕列表")

    override suspend fun getContinueWatching(limit: Int): List<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "Emby 继续观看")

    override suspend fun getResumePosition(itemId: String): Long? =
        throw ProviderException.NotYetImplemented(serverId, "Emby 续播位置")

    @Serializable
    private data class PublicSystemInfo(
        @SerialName("Id") val id: String? = null,
        @SerialName("ServerName") val serverName: String? = null,
        @SerialName("Version") val version: String? = null,
        @SerialName("ProductName") val productName: String? = null,
    )

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "emby",
            displayName = "Emby",
            description = "Emby 媒体服务器",
            category = ProviderCategory.MEDIA_SERVER,
            capabilities = setOf(
                ProviderCapability.AUTH,
                ProviderCapability.LIBRARY,
                ProviderCapability.PLAYBACK,
                ProviderCapability.SEARCH,
                ProviderCapability.SUBTITLE,
                ProviderCapability.PROGRESS,
                ProviderCapability.MULTI_VERSION,
                ProviderCapability.TRANSCODE,
            ),
            authMethod = AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.EXPERIMENTAL,
            sortOrder = 10,
        )
    }
}
