package com.mediahub.provider.emby

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
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
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 该 Provider 类型描述（Factory 与 Provider 共用，见 ADR-015）。 */
internal val EMBY_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "emby",
    serverType = ServerType.EMBY,
    displayName = "Emby",
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
    description = "媒体服务器（Emby）",
)

/** /System/Info/Public 响应（连接测试用，公开端点）。键名按真实 Emby 协议（Id/ServerName/Version）。 */
@Serializable
data class SystemInfoPublic(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

/**
 * Emby Provider（Phase 0.5 骨架）。
 *
 * 已就绪：能力组合声明、协议级连接测试（/System/Info/Public 嗅探）、Token 会话、异常映射。
 * 待实现（Phase 1，见 TASKS.md）：登录 / 媒体库 / 浏览 / 详情 / 播放源解析 / 进度上报。
 */
class EmbyProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger),
    MediaProvider,
    MediaAuthProvider,
    MediaLibraryProvider,
    MediaDetailProvider,
    MediaPlaybackProvider,
    MediaSearchProvider,
    MediaSubtitleProvider,
    MediaProgressProvider {

    override val descriptor: ProviderDescriptor = EMBY_PROVIDER_DESCRIPTOR

    // ---- 连接测试：协议嗅探（ADR-019） ----

    override suspend fun testConnection(): ConnectionStatus {
        return try {
            val start = System.nanoTime()
            val info = apiClient.get<SystemInfoPublic>("${server.baseUrl}/System/Info/Public")
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            // 协议特征校验（ADR-019）：HTTP 200 + JSON 可解析 ≠ 有效 Emby。
            if (info.id.isNullOrBlank() || info.version.isNullOrBlank()) {
                return ConnectionStatus(ok = false, message = "服务器响应不是有效的 Emby SystemInfo")
            }
            ConnectionStatus(
                ok = true,
                latencyMs = latencyMs,
                message = "Emby ${info.version} · ${info.serverName ?: server.displayName}",
            )
        } catch (e: ApiException) {
            ConnectionStatus(
                ok = false,
                message = when (e.statusCode) {
                    401, 403 -> "服务器需要登录（HTTP ${e.statusCode}）"
                    404 -> "该地址不是 Emby 服务（404）"
                    else -> "HTTP ${e.statusCode}"
                },
            )
        } catch (e: ProviderException) {
            ConnectionStatus(ok = false, message = e.message ?: "连接失败")
        } catch (e: Exception) {
            ConnectionStatus(ok = false, message = "连接失败：${e.message}")
        }
    }

    // ---- Auth（占位，Phase 1） ----

    override suspend fun authHeaders(): Map<String, String> = notYet("Emby 会话鉴权头")
    override suspend fun authenticate(credentials: Credentials): AuthResult =
        notYet("Emby 登录（/Users/AuthenticateByName）")
    override suspend fun refreshSession(): AuthResult = notYet("Emby 会话刷新")
    override suspend fun currentUser(): MediaUser? = notYet("Emby 当前用户")

    override suspend fun logout() {
        // 本地会话清理是真实可用的（无需服务端调用）
        clearSession()
    }

    // ---- Library / Detail / Playback / Search / Subtitle / Progress（占位，Phase 1） ----

    override suspend fun getLibraries(): List<MediaLibrary> = notYet("Emby 媒体库（/Users/{userId}/Views）")
    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        notYet("Emby 条目浏览（/Users/{userId}/Items）")
    override suspend fun getSeasons(seriesId: String): List<Season> = notYet("Emby 季列表")
    override suspend fun getEpisodes(seasonId: String): List<Episode> = notYet("Emby 剧集列表")
    override suspend fun getItemDetail(itemId: String): MediaDetail = notYet("Emby 详情")
    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        notYet("Emby 播放源解析（/Items/{itemId}/PlaybackInfo）")
    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        notYet("Emby 搜索（/Search/Hints）")
    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> = notYet("Emby 字幕列表")
    override suspend fun reportProgress(progress: PlaybackProgress) {
        notYet<Unit>("Emby 进度上报（/Sessions/Playing）")
    }
    override suspend fun getContinueWatching(limit: Int): List<MediaItem> = notYet("Emby 继续观看")
    override suspend fun getResumePosition(itemId: String): Long? = notYet("Emby 续播位置")

    private fun <T> notYet(scope: String): T =
        throw ProviderException.NotYetImplemented(serverId, scope)
}
