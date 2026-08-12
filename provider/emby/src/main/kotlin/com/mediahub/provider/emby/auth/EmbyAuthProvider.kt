package com.mediahub.provider.emby.auth

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyUserDto
import com.mediahub.provider.emby.mapper.EmbyUserMapper
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

/**
 * Emby 认证能力（Phase 1A）。
 *
 * - 登录：POST /Users/AuthenticateByName → 严格校验 AccessToken/ServerId/User.Id
 *   → Token 入 TokenStore（按 localServerId），Session 元数据入 EmbySessionStore；
 *   关键字段缺失不保存半成品。
 * - 恢复：TokenStore + SessionStore 双份齐全才尝试真实服务器验证（GET /Users/Me）。
 *   401/403 → 清会话（SessionExpired）；Timeout/DNS/5xx → 保留会话（暂时不可用）。
 * - 登出：服务端 POST /Sessions/Logout 为 best-effort；本地清理为权威（ADR-026）。
 *
 * 密码策略：密码仅存在于登录 HTTP 请求内存中，绝不持久化、绝不进日志（ADR-016）。
 */
class EmbyAuthProvider(
    private val server: MediaServer,
    private val api: EmbyApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: EmbySessionStore,
    private val logger: Logger,
) : MediaAuthProvider {

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val userPassword = credentials as? Credentials.UsernamePassword
            ?: return AuthResult.Failure(
                ProviderException.AuthFailed(server.id, "不支持的凭据类型")
            )
        return try {
            val result = api.authenticate(userPassword.username, userPassword.password)
            val accessToken = result.accessToken
            val remoteServerId = result.serverId
            val userId = result.user?.id
            val userName = result.user?.name

            // 严格校验：关键字段缺失 = 无效响应，不保存半成品会话（ADR-026）
            if (accessToken.isNullOrBlank() || remoteServerId.isNullOrBlank() || userId.isNullOrBlank()) {
                logger.e(LogTag.AUTH, "Emby 登录响应缺少关键字段 serverId=${server.id}")
                return AuthResult.Failure(ProviderException.Parse(server.id, null))
            }

            // 先存 Token；Session 保存失败则回滚 Token（不留孤儿凭据）
            tokenStore.saveTokens(server.id, StoredToken(accessToken = accessToken))
            try {
                sessionStore.save(
                    EmbySession(
                        localServerId = server.id,
                        remoteServerId = remoteServerId,
                        userId = userId,
                        userName = userName.orEmpty(),
                    )
                )
            } catch (e: Exception) {
                tokenStore.clear(server.id)
                throw e
            }
            logger.i(LogTag.AUTH, "Emby 登录成功 serverId=${server.id} remoteServerId=$remoteServerId")
            AuthResult.Success(EmbyUserMapper.map(EmbyUserDto(userId, userName), server.id))
        } catch (e: Exception) {
            mapLoginFailure(e)
        }
    }

    /** Emby 无 refresh-token 流程（本阶段），如实返回未实现。 */
    override suspend fun refreshSession(): AuthResult =
        AuthResult.Failure(ProviderException.NotYetImplemented(server.id, "Emby 无 refresh-token 流程"))

    override suspend fun logout() {
        val token = tokenStore.readTokens(server.id)?.accessToken
        if (token != null) {
            // 服务端撤销：best-effort（网络失败不阻断本地退出）
            runCatching { api.logout(token) }
                .onFailure { logger.w(LogTag.AUTH, "Emby 服务端登出失败（best-effort） serverId=${server.id}", it) }
        }
        // 本地清理：authoritative（ADR-026）
        tokenStore.clear(server.id)
        sessionStore.clear(server.id)
        logger.i(LogTag.AUTH, "Emby 已登出 serverId=${server.id}")
    }

    override suspend fun currentUser(): MediaUser? {
        val session = sessionStore.read(server.id) ?: return null
        return MediaUser(
            serverId = server.id,
            userId = session.userId,
            displayName = session.userName,
        )
    }

    /**
     * 会话恢复 + 验证（App 重启后调用）：
     * - 本地 Token 或 Session 缺失 → SignedOut；
     * - 发起真实服务器验证（GET /Users/Me，需认证）——不无条件信任本地 Token；
     * - 401/403 → 清会话 → Error(SESSION_EXPIRED)；
     * - 网络/服务器暂时不可用 → 保留会话 → Error(对应 kind)。
     */
    suspend fun validateSession(): EmbyAuthState {
        val tokens = tokenStore.readTokens(server.id) ?: return EmbyAuthState.SignedOut
        val session = sessionStore.read(server.id) ?: return EmbyAuthState.SignedOut
        return try {
            val user = api.getCurrentUser(tokens.accessToken)
            EmbyAuthState.Authenticated(EmbyUserMapper.map(user, server.id))
        } catch (e: ApiException) {
            when {
                e.statusCode == 401 || e.statusCode == 403 -> {
                    clearLocalSession()
                    EmbyAuthState.Error(EmbyAuthErrorKind.SESSION_EXPIRED, "登录已失效，请重新登录")
                }

                e.statusCode in 500..599 -> EmbyAuthState.Error(
                    EmbyAuthErrorKind.SERVER_ERROR,
                    "服务器错误（HTTP ${e.statusCode}）",
                )

                else -> EmbyAuthState.Error(
                    EmbyAuthErrorKind.SERVER_ERROR,
                    "验证失败（HTTP ${e.statusCode}）",
                )
            }
        } catch (e: SerializationException) {
            clearLocalSession()
            EmbyAuthState.Error(EmbyAuthErrorKind.INVALID_RESPONSE, "服务器响应异常，请重新登录")
        } catch (e: IOException) {
            val kind = when (e) {
                is SocketTimeoutException -> EmbyAuthErrorKind.NETWORK_TIMEOUT
                is UnknownHostException, is ConnectException -> EmbyAuthErrorKind.NETWORK_UNAVAILABLE
                else -> EmbyAuthErrorKind.NETWORK_UNAVAILABLE
            }
            // 网络问题 ≠ token 失效：保留本地会话
            EmbyAuthState.Error(kind, "无法连接服务器，请稍后重试")
        } catch (e: Exception) {
            EmbyAuthState.Error(EmbyAuthErrorKind.UNKNOWN, "验证失败：${e.message}")
        }
    }

    private suspend fun clearLocalSession() {
        tokenStore.clear(server.id)
        sessionStore.clear(server.id)
    }

    private fun mapLoginFailure(e: Exception): AuthResult.Failure = when (e) {
        is ApiException -> when {
            e.statusCode == 401 || e.statusCode == 403 ->
                AuthResult.Failure(ProviderException.AuthFailed(server.id, "用户名或密码错误"))
            e.statusCode in 500..599 ->
                AuthResult.Failure(ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId))
            else ->
                AuthResult.Failure(ProviderException.Http(server.id, e.statusCode, e.url, e.method, e.requestId))
        }

        is SerializationException ->
            AuthResult.Failure(ProviderException.Parse(server.id, e))

        is IOException ->
            AuthResult.Failure(ProviderException.Network(server.id, e))

        else ->
            AuthResult.Failure(ProviderException.Unknown(server.id, e))
    }
}
