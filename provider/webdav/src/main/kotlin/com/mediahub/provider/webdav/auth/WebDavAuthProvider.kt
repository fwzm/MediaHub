package com.mediahub.provider.webdav.auth

import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.api.SessionRestoreResult
import java.nio.charset.StandardCharsets
import okhttp3.Credentials as OkHttpCredentials

/** Basic 凭据协议适配器；会话持久化仍由 AuthenticationCoordinator 统一负责。 */
class WebDavAuthProvider(
    private val server: MediaServer,
    private val apiClient: ApiClient,
) : MediaAuthProvider {
    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val basic = credentials as? Credentials.BasicAuth
            ?: return AuthResult.Failure(ProviderException.AuthFailed(server.id, "WebDAV 需要 Basic 凭据"))
        val result = protectedProbe(basic)
        if (result !is ServerProbeResult.Success || result.httpCode !in 200..299) {
            val detail = when (result) {
                is ServerProbeResult.Success -> "凭据验证失败（HTTP ${result.httpCode}）"
                is ServerProbeResult.Failure -> result.userMessage
            }
            return AuthResult.Failure(ProviderException.AuthFailed(server.id, detail))
        }
        return AuthResult.Success(
            AuthSession(
                credential = SessionCredential.BasicAuth(basic.username, basic.password),
                user = MediaUser(server.id, basic.username, basic.username),
            )
        )
    }

    override suspend fun restoreSession(session: AuthSession): SessionRestoreResult {
        val basic = session.credential as? SessionCredential.BasicAuth
            ?: return SessionRestoreResult.Invalidated(ProviderException.AuthExpired(server.id))
        return when (val result = authenticate(Credentials.BasicAuth(basic.username, basic.password))) {
            is AuthResult.Success -> SessionRestoreResult.Restored(result.session)
            is AuthResult.Failure -> SessionRestoreResult.Unavailable(result.error)
        }
    }

    override suspend fun logout(session: AuthSession) = Unit

    private suspend fun protectedProbe(credentials: Credentials.BasicAuth): ServerProbeResult {
        val defaultResult = probe(credentials, useUtf8 = false)
        if (defaultResult !is ServerProbeResult.Success || defaultResult.httpCode != 401) {
            return defaultResult
        }
        val challenge = defaultResult.responseHeaders.entries
            .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
            ?.value
        return if (challenge?.contains("charset=\"UTF-8\"", ignoreCase = true) == true) {
            probe(credentials, useUtf8 = true)
        } else {
            defaultResult
        }
    }

    private suspend fun probe(credentials: Credentials.BasicAuth, useUtf8: Boolean): ServerProbeResult =
        apiClient.probe(
            server.baseUrl,
            method = "PROPFIND",
            headers = mapOf(
                "Authorization" to if (useUtf8) {
                    OkHttpCredentials.basic(credentials.username, credentials.password, StandardCharsets.UTF_8)
                } else {
                    OkHttpCredentials.basic(credentials.username, credentials.password)
                },
                "Depth" to "0",
            ),
        )
}
