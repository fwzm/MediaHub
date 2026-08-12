package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.base.BaseMediaServerProvider
import java.nio.charset.StandardCharsets
import java.util.Base64

/** WebDAV Phase 0.5 骨架；OPTIONS 协议探测和 Basic 凭据生命周期已实现。 */
class WebDavProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    credentialVault: CredentialVault,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, credentialVault, logger),
    MediaAuthProvider,
    MediaBrowseProvider,
    MediaPlaybackProvider {

    override val descriptor: ProviderDescriptor = DESCRIPTOR

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus {
        val headers = when (val credentials = request.credentials) {
            is Credentials.BasicAuth -> mapOf("Authorization" to basicHeader(credentials))
            else -> emptyMap()
        }
        return when (val result = apiClient.probe(server.baseUrl, method = "OPTIONS", headers = headers)) {
            is ServerProbeResult.Success -> {
                val davHeader = result.responseHeaders.entries
                    .firstOrNull { it.key.equals("DAV", ignoreCase = true) }
                    ?.value
                val valid = result.httpCode in 200..299 && !davHeader.isNullOrBlank()
                ConnectionStatus(
                    ok = valid,
                    latencyMs = result.latencyMs,
                    message = if (valid) {
                        "WebDAV $davHeader · ${result.latencyMs}ms"
                    } else {
                        "响应未声明 WebDAV 能力（HTTP ${result.httpCode}）"
                    },
                    errorCode = if (valid) null else ProviderException.ErrorCode.CONNECTION,
                )
            }

            is ServerProbeResult.Failure -> ConnectionStatus(
                ok = false,
                message = result.userMessage,
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val basic = credentials as? Credentials.BasicAuth
            ?: return AuthResult.Failure(ProviderException.AuthFailed(serverId, "WebDAV 需要 Basic 凭据"))
        val status = testConnection(ConnectionTestRequest(credentials = basic))
        if (!status.ok) return AuthResult.Failure(ProviderException.AuthFailed(serverId, status.message))
        return AuthResult.Success(
            user = MediaUser(serverId = serverId, userId = basic.username, displayName = basic.username),
            session = SessionCredential.BasicAuth(basic.username, basic.password),
        )
    }

    override suspend fun refreshSession(): AuthResult {
        val session = requireSession() as? SessionCredential.BasicAuth
            ?: return AuthResult.Failure(ProviderException.AuthRequired(serverId))
        return authenticate(Credentials.BasicAuth(session.username, session.password))
    }

    override suspend fun logout() = clearCredentials()

    override suspend fun currentUser(): MediaUser? =
        (credentialVault.readSession(serverId) as? SessionCredential.BasicAuth)?.let {
            MediaUser(serverId = serverId, userId = it.username, displayName = it.username)
        }

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV PROPFIND 列目录")

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 播放源解析")

    private fun basicHeader(credentials: Credentials.BasicAuth): String {
        val value = "${credentials.username}:${credentials.password}"
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
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
