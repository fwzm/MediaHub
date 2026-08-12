package com.mediahub.provider.emby.auth

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.api.SessionRestoreResult
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyUserMapper
import com.mediahub.provider.emby.mapper.EmbyAuthErrorMapper
import com.mediahub.provider.emby.session.EmbySessionValidator

/**
 * 无状态 Emby 认证协议适配器。它只交换/验证/撤销 Session；
 * 持久化与清理由 AuthenticationCoordinator + CredentialVault 独占。
 */
class EmbyAuthProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val sessionValidator: EmbySessionValidator,
    private val logger: Logger,
) : MediaAuthProvider {
    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val input = credentials as? Credentials.UsernamePassword
            ?: return AuthResult.Failure(ProviderException.AuthFailed(server.id, "不支持的凭据类型"))
        return try {
            val result = api.authenticate(input.username, input.password)
            val token = result.accessToken?.takeIf(String::isNotBlank)
            val remoteServerId = result.serverId?.takeIf(String::isNotBlank)
            val user = result.user?.takeIf { !it.id.isNullOrBlank() }
            if (token == null || remoteServerId == null || user == null) {
                logger.e(LogTag.AUTH, "Emby 登录响应缺少关键字段 serverId=${server.id}")
                return AuthResult.Failure(ProviderException.Parse(server.id))
            }
            AuthResult.Success(
                AuthSession(
                    credential = SessionCredential.AccessToken(token),
                    user = EmbyUserMapper.map(user, server.id),
                    remoteServerId = remoteServerId,
                )
            )
        } catch (e: Exception) {
            AuthResult.Failure(EmbyAuthErrorMapper.map(e, server.id, login = true))
        }
    }

    override suspend fun restoreSession(session: AuthSession): SessionRestoreResult =
        sessionValidator.validate(session)

    override suspend fun logout(session: AuthSession) {
        val token = (session.credential as? SessionCredential.AccessToken)?.accessToken ?: return
        api.logout(token, session.user.userId)
    }
}
