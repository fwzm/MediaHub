package com.mediahub.provider.emby.auth

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiException
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Emby 认证能力（Phase 1A + finalization）。
 *
 * - 登录：POST /emby/Users/AuthenticateByName → 严格校验 AccessToken/ServerId/User.Id
 *   → Token 入 TokenStore（按 localServerId），Session 元数据入 EmbySessionStore；
 *   关键字段缺失不保存半成品。
 * - 恢复（restoreSession）：Token + Session 双份齐全后，**先无 Token 校验服务器身份**
 *   （/System/Info/Public 的 ServerId 与 session.remoteServerId 对比，防 Token 串服），
 *   一致才发认证请求（GET /Users/{userId}）。401 → 清会话；403/5xx/网络/协议异常 → 保留会话。
 * - 登出：服务端 POST /emby/Sessions/Logout 为 best-effort（且仅当服务器身份一致），
 *   本地清理为权威（ADR-026）。
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

    private val authenticationLease = tokenStore.authenticationLease(server.id)

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val userPassword = credentials as? Credentials.UsernamePassword
            ?: return AuthResult.Failure(
                ProviderException.AuthFailed(server.id, "不支持的凭据类型")
            )
        val attempt = tokenStore.beginAuthentication(authenticationLease)
            ?: return AuthResult.Failure(ProviderException.AuthFailed(server.id, "媒体源已更新，请重新打开登录"))
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

            val committed = tokenStore.commitAuthentication(
                attempt, StoredToken(accessToken = accessToken), saveSession = {
                sessionStore.save(
                    EmbySession(
                        localServerId = server.id,
                        remoteServerId = remoteServerId,
                        userId = userId,
                        userName = userName.orEmpty(),
                    )
                )
                }, clearSession = { sessionStore.clear(server.id) },
            )
            if (!committed) return AuthResult.Failure(ProviderException.AuthFailed(server.id, "登录已失效，请重新登录"))
            logger.i(LogTag.AUTH, "Emby 登录成功 serverId=${server.id} remoteServerId=$remoteServerId")
            AuthResult.Success(EmbyUserMapper.map(EmbyUserDto(userId, userName), server.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapLoginFailure(e)
        }
    }

    /** Emby 无 refresh-token 流程（本阶段），如实返回未实现。 */
    override suspend fun refreshSession(): AuthResult =
        AuthResult.Failure(ProviderException.NotYetImplemented(server.id, "Emby 无 refresh-token 流程"))

    /**
     * 会话恢复 + 验证（App 启动 / 首页进入时调用）。
     *
     * 顺序（review #2 防串服）：
     * 1. 本地 Token / Session 缺失 → SignedOut；
     * 2. **无 Token** GET /System/Info/Public → 当前服务器 remoteServerId；
     * 3. 与 session.remoteServerId 不一致 → SERVER_MISMATCH（绝不发送 Token）；
     * 4. 一致 → GET /Users/{userId}（X-Emby-Token）验证。
     *
     * 失效策略（review #4）：仅 401 清会话；403/5xx/网络/协议异常保留。
     */
    override suspend fun restoreSession(): AuthSessionState {
        val attempt = tokenStore.beginAuthentication(authenticationLease) ?: return AuthSessionState.SignedOut
        val tokens = tokenStore.readTokens(server.id) ?: return AuthSessionState.SignedOut
        val session = sessionStore.read(server.id) ?: return AuthSessionState.SignedOut
        if (!tokenStore.isAuthenticationCurrent(attempt)) return AuthSessionState.SignedOut

        // 服务器身份校验：无 Token 请求，绝不对错误服务器发送旧 Token
        val currentRemoteServerId = try {
            api.getSystemInfoPublic().id
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return authErrorFromHttp(e, preserveSession = true, attempt)
        } catch (e: SerializationException) {
            return AuthSessionState.Error(
                AuthSessionErrorKind.INVALID_RESPONSE,
                "服务器响应异常，无法确认服务器身份",
            )
        } catch (e: IOException) {
            return AuthSessionState.Error(networkKind(e), "无法连接服务器，请稍后重试")
        } catch (e: Exception) {
            return AuthSessionState.Error(AuthSessionErrorKind.UNKNOWN, "验证失败：${e.message}")
        }

        if (!tokenStore.isAuthenticationCurrent(attempt)) return AuthSessionState.SignedOut
        if (currentRemoteServerId.isNullOrBlank()) {
            return AuthSessionState.Error(
                AuthSessionErrorKind.INVALID_RESPONSE,
                "服务器未返回有效身份标识",
            )
        }
        if (currentRemoteServerId != session.remoteServerId) {
            logger.w(LogTag.AUTH, "Emby 服务器身份变更 serverId=${server.id} saved=${session.remoteServerId} current=$currentRemoteServerId")
            return AuthSessionState.Error(
                AuthSessionErrorKind.SERVER_MISMATCH,
                "服务器身份已变更，请重新登录",
            )
        }

        return try {
            val user = api.getCurrentUser(tokens.accessToken, session.userId)
            if (!tokenStore.isAuthenticationCurrent(attempt)) return AuthSessionState.SignedOut
            AuthSessionState.Authenticated(EmbyUserMapper.map(user, server.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 仅 401（Token 已撤销）才清会话；403/5xx 保留（review #4）
            authErrorFromHttp(e, preserveSession = e.statusCode != 401, attempt)
        } catch (e: SerializationException) {
            // 协议异常 ≠ 认证失效：保留会话（review #4）
            AuthSessionState.Error(AuthSessionErrorKind.INVALID_RESPONSE, "服务器响应异常，请稍后重试")
        } catch (e: IOException) {
            AuthSessionState.Error(networkKind(e), "无法连接服务器，请稍后重试")
        } catch (e: Exception) {
            AuthSessionState.Error(AuthSessionErrorKind.UNKNOWN, "验证失败：${e.message}")
        }
    }

    override suspend fun logout() {
        val attempt = tokenStore.beginAuthentication(authenticationLease) ?: return
        var cancellation: CancellationException? = null
        try {
            val tokens = tokenStore.readTokens(server.id)
            val session = sessionStore.read(server.id)
            if (!tokenStore.isAuthenticationCurrent(attempt)) return
            if (tokens != null && session != null) {
                // Only the current handle may start another request after the anonymous probe.
                val remoteId = try { api.getSystemInfoPublic().id }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (remoteId == session.remoteServerId && tokenStore.isAuthenticationCurrent(attempt)) {
                    try { api.logout(tokens.accessToken, session.userId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logger.w(LogTag.AUTH, "Emby 服务端登出失败（best-effort） serverId=${server.id}", e) }
                }
            }
        } catch (e: CancellationException) {
            cancellation = e
            throw e
        } finally {
            try { tokenStore.clearAuthenticationIfCurrent(attempt) { sessionStore.clear(server.id) } }
            catch (cleanup: Exception) { cancellation?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    override suspend fun currentUser(): MediaUser? {
        val attempt = tokenStore.beginAuthentication(authenticationLease) ?: return null
        val session = sessionStore.read(server.id) ?: return null
        if (!tokenStore.isAuthenticationCurrent(attempt)) return null
        return MediaUser(
            serverId = server.id,
            userId = session.userId,
            displayName = session.userName,
        )
    }

    /** HTTP 错误 → 会话状态；[preserveSession] 为 false 时（401）清理本地会话。 */
    private suspend fun authErrorFromHttp(
        e: ApiException,
        preserveSession: Boolean,
        attempt: TokenStore.AuthenticationAttempt,
    ): AuthSessionState {
        if (!tokenStore.isAuthenticationCurrent(attempt)) return AuthSessionState.SignedOut
        if (!preserveSession && !tokenStore.clearAuthenticationIfCurrent(attempt) { sessionStore.clear(server.id) }) {
            return AuthSessionState.SignedOut
        }
        return when {
            e.statusCode == 401 -> AuthSessionState.Error(AuthSessionErrorKind.SESSION_EXPIRED, "登录已失效，请重新登录")
            e.statusCode == 403 -> AuthSessionState.Error(AuthSessionErrorKind.FORBIDDEN, "没有访问权限（403）")
            e.statusCode in 500..599 -> AuthSessionState.Error(AuthSessionErrorKind.SERVER_ERROR, "服务器错误（HTTP ${e.statusCode}）")
            else -> AuthSessionState.Error(AuthSessionErrorKind.UNKNOWN, "验证失败（HTTP ${e.statusCode}）")
        }
    }

    private fun networkKind(e: IOException): AuthSessionErrorKind = when (e) {
        is SocketTimeoutException -> AuthSessionErrorKind.NETWORK_TIMEOUT
        is UnknownHostException, is ConnectException -> AuthSessionErrorKind.NETWORK_UNAVAILABLE
        else -> AuthSessionErrorKind.NETWORK_UNAVAILABLE
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
