package com.mediahub.provider.emby

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
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
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.SystemInfoPublic

/** 该 Provider 类型描述（declaredCapabilities = Emby 最终计划能力；运行时以 Handle 为准，ADR-022/026）。 */
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

/**
 * Emby Provider（Phase 1A）：只承载公共身份 / descriptor / 协议级连接测试。
 *
 * 具体能力按内部模块拆分（避免巨型类，见 HANDOFF）：
 * - 认证：auth/EmbyAuthProvider（Phase 1A 已实现）
 * - 媒体库/详情/播放/搜索/字幕/进度：Phase 1B 起逐项实现，届时在 Factory 对应填 Handle 字段。
 */
class EmbyProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
    private val authHeaderBuilder: EmbyAuthorizationHeaderBuilder,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger),
    MediaProvider {

    override val descriptor: ProviderDescriptor = EMBY_PROVIDER_DESCRIPTOR

    // ---- 连接测试：协议嗅探（ADR-019） ----

    override suspend fun testConnection(): ConnectionStatus {
        return try {
            val start = System.nanoTime()
            val info = apiClient.get<SystemInfoPublic>("${server.baseUrl}/System/Info/Public")
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            // 协议特征校验（ADR-024）：HTTP 200 + JSON 可解析 ≠ 有效 Emby。
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

    /** 客户端身份头（未认证场景，如连接测试；登录后的认证头由 EmbyApiClient 统一管理）。 */
    override suspend fun authHeaders(): Map<String, String> =
        mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build())
}
