package com.mediahub.provider.jellyfin

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinSystemInfoPublic

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
 * - 认证能力由 auth/JellyfinAuthProvider 承载（Factory 组装进 Handle.auth）；
 * - LIBRARY/DETAIL/SEARCH 由 1G-B 的 library/detail/search 独立类承载（Factory 组装）；
 *   PLAYBACK/PROGRESS 待后续 slice；IDENTITY_LOOKUP 因协议缺口保持 null（DEFER）；
 * - 本类只承载公共身份 / descriptor / 连接测试（镜像 EmbyProvider 结构）。
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
    MediaProvider {

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
}
