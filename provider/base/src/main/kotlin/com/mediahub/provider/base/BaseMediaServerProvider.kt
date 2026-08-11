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
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderException
import java.io.IOException

/**
 * 媒体服务器型 Provider 的共享基类（Emby / Jellyfin / Plex 等）。
 *
 * 职责：
 * - 统一异常映射（[ApiException]/[IOException] → [ProviderException]）；
 * - Token 会话的读取/清理；
 * - 通用连通性探测（[testConnection]）。
 *
 * 注意：协议差异（endpoint、参数、鉴权头格式）由子类实现，
 * 禁止为了复用把差异埋进 if/else。
 */
abstract class BaseMediaServerProvider(
    protected val server: MediaServer,
    protected val apiClient: ApiClient,
    protected val mediaHttpClient: MediaHttpClient,
    protected val tokenStore: TokenStore,
    protected val logger: Logger,
) : MediaProvider {

    final override val serverId: String get() = server.id
    final override val type = server.type
    final override val displayName: String get() = server.displayName

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

    /** 子类提供"当前会话"的鉴权头（如 X-Emby-Token / X-Emby-Authorization）。 */
    protected abstract suspend fun authHeaders(): Map<String, String>

    override suspend fun testConnection(): ConnectionStatus =
        when (val result = apiClient.probe(server.baseUrl)) {
            is ServerProbeResult.Success -> ConnectionStatus(
                ok = result.httpCode < 500,
                latencyMs = result.latencyMs,
                message = "HTTP ${result.httpCode} · ${result.latencyMs}ms",
            )

            is ServerProbeResult.Failure -> ConnectionStatus(
                ok = false,
                message = result.userMessage,
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }

    override suspend fun logout() {
        tokenStore.clear(server.id)
        logger.i(LogTag.AUTH, "已登出 serverId=${server.id}")
    }

    /** 子类覆盖并返回各自能力集。 */
    override fun capabilities(): Set<ProviderCapability> = emptySet()
}
