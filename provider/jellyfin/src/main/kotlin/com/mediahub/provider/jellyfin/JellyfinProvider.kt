package com.mediahub.provider.jellyfin

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
import com.mediahub.model.Episode
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProgressProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.MediaSubtitleProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinSystemInfoPublic
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder

/** 该 Provider 类型描述（Factory 与 Provider 共用，见 ADR-015）。 */
internal val JELLYFIN_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "jellyfin",
    serverType = ServerType.JELLYFIN,
    displayName = "Jellyfin",
    category = ProviderCategory.MEDIA_SERVER,
    declaredCapabilities = setOf(
        ProviderCapability.AUTH,
        ProviderCapability.LIBRARY,
        ProviderCapability.DETAIL,
        ProviderCapability.PLAYBACK,
        ProviderCapability.SEARCH,
        ProviderCapability.SUBTITLE,
        ProviderCapability.PROGRESS,
        ProviderCapability.MULTI_VERSION,
        ProviderCapability.TRANSCODE,
    ),
    authMethod = AuthMethod.USERNAME_PASSWORD,
    status = ProviderStatus.EXPERIMENTAL,
    description = "媒体服务器（Jellyfin）",
    probePath = "/System/Info/Public",
)

/**
 * Jellyfin Provider（Phase 1G 独立 Connector，ADR-039）。
 *
 * - 协议级连接测试（/System/Info/Public 嗅探，ADR-019/024）经 [JellyfinApiClient]，
 *   baseUrl 原样保留反代子路径；
 * - 认证能力由 auth/JellyfinAuthProvider 承载（Factory 组装进 Handle.auth），
 *   本类不再实现 MediaAuthProvider；
 * - 其余 capability 待后续 slice 逐项实现（notYet stub，Handle 保持 null）。
 */
class JellyfinProvider(
    server: MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
    private val jellyfinApi: JellyfinApiClient,
    private val authHeaderBuilder: JellyfinAuthorizationHeaderBuilder,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger),
    MediaProvider,
    MediaLibraryProvider,
    MediaDetailProvider,
    MediaPlaybackProvider,
    MediaSearchProvider,
    MediaSubtitleProvider,
    MediaProgressProvider {

    override val descriptor: ProviderDescriptor = JELLYFIN_PROVIDER_DESCRIPTOR

    override suspend fun testConnection(): ConnectionStatus {
        return try {
            val start = System.nanoTime()
            val info = jellyfinApi.getSystemInfoPublic()
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            // 协议特征校验（ADR-019）：HTTP 200 + JSON 可解析 ≠ 有效 Jellyfin。
            if (info.id.isNullOrBlank() || info.version.isNullOrBlank()) {
                return ConnectionStatus(ok = false, message = "服务器响应不是有效的 Jellyfin SystemInfo")
            }
            ConnectionStatus(
                ok = true,
                latencyMs = latencyMs,
                message = "Jellyfin ${info.version} · ${info.serverName ?: server.displayName}",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消红线：绝不把取消折叠成 ConnectionStatus(false)（ADR-039）
            throw e
        } catch (e: ApiException) {
            ConnectionStatus(
                ok = false,
                message = when (e.statusCode) {
                    401, 403 -> "服务器需要登录（HTTP ${e.statusCode}）"
                    404 -> "该地址不是 Jellyfin 服务（404）"
                    else -> "HTTP ${e.statusCode}"
                },
            )
        } catch (e: ProviderException) {
            ConnectionStatus(ok = false, message = e.message ?: "连接失败")
        } catch (e: Exception) {
            ConnectionStatus(ok = false, message = "连接失败：${e.message}")
        }
    }

    /** 客户端身份头（未认证场景）：标准 `Authorization: MediaBrowser …`，无 Token（ADR-039）。 */
    override suspend fun authHeaders(): Map<String, String> =
        mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build())

    override suspend fun getLibraries(): List<MediaLibrary> = notYet("Jellyfin 媒体库（/Users/{userId}/Views）")
    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        notYet("Jellyfin 条目浏览（/Users/{userId}/Items）")
    override suspend fun getSeasons(seriesId: String): List<Season> = notYet("Jellyfin 季列表")
    override suspend fun getEpisodes(seasonId: String): List<Episode> = notYet("Jellyfin 剧集列表")
    override suspend fun getItemDetail(itemId: String): MediaDetail = notYet("Jellyfin 详情")
    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        notYet("Jellyfin 播放源解析（/Items/{itemId}/PlaybackInfo）")
    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        notYet("Jellyfin 搜索")
    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> = notYet("Jellyfin 字幕列表")
    override suspend fun reportProgress(progress: PlaybackProgress) {
        notYet<Unit>("Jellyfin 进度上报（/Sessions/Playing）")
    }
    override suspend fun getContinueWatching(limit: Int): List<MediaItem> = notYet("Jellyfin 继续观看")
    override suspend fun getResumePosition(itemId: String): Long? = notYet("Jellyfin 续播位置")

    private fun <T> notYet(scope: String): T =
        throw ProviderException.NotYetImplemented(serverId, scope)
}
