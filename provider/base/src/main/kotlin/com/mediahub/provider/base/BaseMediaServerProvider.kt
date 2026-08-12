package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderException
import java.io.IOException

/** 媒体服务器 Connector 的异常映射与加密会话基础设施。协议探测由子类实现。 */
abstract class BaseMediaServerProvider(
    protected val server: MediaServer,
    protected val apiClient: ApiClient,
    protected val mediaHttpClient: MediaHttpClient,
    protected val logger: Logger,
) : MediaProvider {

    final override val serverId: String get() = server.id

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

    /** 把具体协议探测统一映射为 UI 可消费状态，同时保留结构化错误。 */
    protected suspend fun connectionCheck(block: suspend () -> String): ConnectionStatus {
        val startedAt = System.nanoTime()
        return try {
            val message = apiCall(block)
            ConnectionStatus(
                ok = true,
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                message = message,
            )
        } catch (e: ProviderException) {
            ConnectionStatus(
                ok = false,
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                message = e.message,
                errorCode = e.code,
            )
        }
    }
}
