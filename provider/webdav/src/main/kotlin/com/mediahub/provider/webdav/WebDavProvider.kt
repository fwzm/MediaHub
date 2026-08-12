package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import okhttp3.Credentials as OkHttpCredentials

/** WebDAV 公共协议探测；认证独立在 auth/WebDavAuthProvider。 */
class WebDavProvider(
    server: MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, logger) {
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus {
        val headers = when (val credentials = request.credentials) {
            is Credentials.BasicAuth -> mapOf(
                "Authorization" to OkHttpCredentials.basic(credentials.username, credentials.password)
            )
            else -> emptyMap()
        }
        return when (val result = apiClient.probe(server.baseUrl, method = "OPTIONS", headers = headers)) {
            is ServerProbeResult.Success -> {
                val dav = result.responseHeaders.entries
                    .firstOrNull { it.key.equals("DAV", ignoreCase = true) }
                    ?.value
                val authRequired = result.httpCode == 401 || result.httpCode == 403
                val valid = result.httpCode in 200..299 && !dav.isNullOrBlank()
                ConnectionStatus(
                    ok = valid,
                    latencyMs = result.latencyMs,
                    message = when {
                        valid -> "WebDAV $dav · ${result.latencyMs}ms"
                        authRequired -> "WebDAV 服务器需要认证（HTTP ${result.httpCode}）"
                        else -> "响应未声明 WebDAV 能力（HTTP ${result.httpCode}）"
                    },
                    errorCode = when {
                        valid -> null
                        authRequired -> ProviderException.ErrorCode.AUTH_REQUIRED
                        else -> ProviderException.ErrorCode.CONNECTION
                    },
                )
            }
            is ServerProbeResult.Failure -> ConnectionStatus(
                ok = false,
                message = result.userMessage,
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }
    }

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "webdav",
            displayName = "WebDAV",
            description = "WebDAV / NAS 通用协议",
            category = ProviderCategory.NETWORK_STORAGE,
            capabilities = setOf(
                ProviderCapability.AUTH,
                ProviderCapability.BROWSE,
                ProviderCapability.PLAYBACK,
            ),
            authMethod = AuthMethod.BASIC,
            status = ProviderStatus.EXPERIMENTAL,
            sortOrder = 30,
        )
    }
}
