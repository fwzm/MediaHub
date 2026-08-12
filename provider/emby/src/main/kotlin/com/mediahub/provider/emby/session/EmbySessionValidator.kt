package com.mediahub.provider.emby.session

import com.mediahub.core.network.ApiException
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.api.SessionRestoreResult
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.mapper.EmbyAuthErrorMapper
import com.mediahub.provider.emby.mapper.EmbyUserMapper

/**
 * Emby 会话协议验证；不持久化任何状态。CredentialVault 仍是唯一 source of truth。
 */
class EmbySessionValidator(
    private val server: MediaServer,
    private val api: EmbyApiClient,
) {
    suspend fun validate(session: AuthSession): SessionRestoreResult {
        val token = (session.credential as? SessionCredential.AccessToken)?.accessToken
            ?: return SessionRestoreResult.Invalidated(ProviderException.AuthExpired(server.id))
        val expectedServerId = session.remoteServerId?.takeIf(String::isNotBlank)
            ?: return SessionRestoreResult.Invalidated(ProviderException.AuthExpired(server.id))

        return try {
            // 公开身份探测不带 Token；只有 remoteServerId 匹配后才发送认证 Header。
            val actualServerId = api.publicSystemInfo().id
            if (actualServerId.isNullOrBlank() || actualServerId != expectedServerId) {
                return SessionRestoreResult.Invalidated(
                    ProviderException.Connection(server.id, "服务器身份已变化，请重新登录")
                )
            }
            val remoteUser = api.currentUser(token, session.user.userId)
            val userId = remoteUser.id?.takeIf(String::isNotBlank)
                ?: return SessionRestoreResult.Unavailable(ProviderException.Parse(server.id))
            if (userId != session.user.userId) {
                return SessionRestoreResult.Invalidated(ProviderException.AuthExpired(server.id))
            }
            SessionRestoreResult.Restored(session.copy(user = EmbyUserMapper.map(remoteUser, server.id)))
        } catch (error: ApiException) {
            // 官方文档只把 401 定义为 Token 已撤销；403 不足以证明会话失效。
            if (error.statusCode == 401) {
                SessionRestoreResult.Invalidated(ProviderException.AuthExpired(server.id))
            } else {
                SessionRestoreResult.Unavailable(EmbyAuthErrorMapper.map(error, server.id, login = false))
            }
        } catch (error: Exception) {
            SessionRestoreResult.Unavailable(EmbyAuthErrorMapper.map(error, server.id, login = false))
        }
    }
}
