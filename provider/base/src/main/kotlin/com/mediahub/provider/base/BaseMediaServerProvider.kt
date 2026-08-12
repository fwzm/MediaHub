package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ProviderException
import java.io.IOException

/**
 * 媒体服务器型 Provider 的共享基类（Emby / Jellyfin / Plex 等）。
 *
 * 只提供公共设施，不实现 [com.mediahub.provider.api.MediaProvider] 的能力契约：
 * - 统一异常映射（[ApiException]/[IOException] → [ProviderException]）；
 * - Token 会话读取/清理；
 * - 通用 HTTP 探测（[probeBaseUrl]）与结果转换。
 *
 * 协议差异（endpoint、鉴权头、连接测试语义）由子类实现，禁止塞进 if/else。
 */
abstract class BaseMediaServerProvider(
    protected val server: MediaServer,
    protected val apiClient: ApiClient,
    protected val mediaHttpClient: MediaHttpClient,
    protected val tokenStore: TokenStore,
    protected val logger: Logger,
) {
    final val serverId: String get() = server.id
    final val type: ServerType get() = server.type
    val displayName: String get() = server.displayName

    /** 统一 API 调用包装：异常收敛为 [ProviderException]（日志脱敏）。 */
    protected suspend fun <T> apiCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ProviderException) {
            throw e
        } catch (e: ApiException) {
            logger.w(LogTag.PROVIDER, e.toLogString())
            throw when (e.statusCode) {
                401 -> ProviderException.AuthExpired(server.id)
                403 -> ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId)
                404 -> ProviderException.NotFound(server.id, e.url)
                429 -> ProviderException.RateLimited(server.id)
                else -> ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId)
            }
        } catch (e: IOException) {
            throw ProviderException.Network(server.id, e)
        } catch (e: Exception) {
            logger.e(LogTag.PROVIDER, "Provider 未知异常 serverId=${server.id}", e)
            throw ProviderException.Unknown(server.id, e)
        }

    /** 读取当前会话 Token；未登录抛 [ProviderException.AuthRequired]。 */
    protected suspend fun requireTokens(): StoredToken =
        tokenStore.readTokens(server.id) ?: throw ProviderException.AuthRequired(server.id)

    /** 子类提供"当前会话"的鉴权头（如 X-Emby-Token / X-Emby-Authorization / Basic）。 */
    protected abstract suspend fun authHeaders(): Map<String, String>

    /** 通用 HTTP 探测（只证明"地址有 HTTP 响应"，协议判定由子类 testConnection 完成）。 */
    protected suspend fun probeBaseUrl(): ServerProbeResult = apiClient.probe(server.baseUrl)

    protected fun ServerProbeResult.toConnectionStatus(): ConnectionStatus = when (this) {
        is ServerProbeResult.Success -> ConnectionStatus(
            ok = httpCode < 500,
            latencyMs = latencyMs,
            message = "HTTP $httpCode · ${latencyMs}ms",
        )

        is ServerProbeResult.Failure -> ConnectionStatus(
            ok = false,
            message = userMessage,
            errorCode = ProviderException.ErrorCode.CONNECTION,
        )
    }

    /** 清理会话 Token（AuthProvider.logout 实现用）。 */
    protected suspend fun clearSession() {
        tokenStore.clear(server.id)
        logger.i(LogTag.AUTH, "已登出 serverId=${server.id}")
    }
}
