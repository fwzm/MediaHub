package com.mediahub.provider.jellyfin.auth

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
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.session.JellyfinSession
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException

/**
 * Jellyfin 认证能力（Phase 1G-A，ADR-039 冻结 contract；协调者角色，结构镜像已封板的
 * EmbyAuthProvider，协议差异各自实现）。
 *
 * - 登录：POST /Users/AuthenticateByName（密码只在 body，绝不持久化/日志）→ 严格校验
 *   AccessToken/ServerId/User.Id → Token 入 TokenStore（按 localServerId），
 *   Session 元数据入 JellyfinSessionStore；关键字段缺失不保存半成品。
 * - 恢复（restoreSession）：Token + Session 双份齐全后，**先无 Token 校验服务器身份**
 *   （/System/Info/Public 与 session.remoteServerId 对比，防 Token 串服），一致才发认证请求。
 *   失效策略（contract §2.3 冻结文本）：**401/403 均清本地会话**；5xx/网络/协议异常保留
 *   ——注意这是对 Emby sealed 行为（仅 401 清）的有意分歧，ADR-039 已记录。
 * - 登出：POST /Sessions/Logout best-effort（且仅当服务器身份一致），本地清理为权威。
 */
class JellyfinAuthProvider(
    private val server: MediaServer,
    private val api: JellyfinApiClient,
    private val tokenStore: TokenStore,
    private val sessionStore: JellyfinSessionStore,
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
            val remoteServerId = result.resolvedServerId
            val userId = result.user?.id
            val userName = result.user?.name

            // 严格校验：关键字段缺失 = 无效响应，不保存半成品会话（ADR-026）
            if (accessToken.isNullOrBlank() || remoteServerId.isNullOrBlank() || userId.isNullOrBlank()) {
                logger.e(LogTag.AUTH, "Jellyfin 登录响应缺少关键字段 serverId=${server.id}")
                return AuthResult.Failure(ProviderException.Parse(server.id, null))
            }

            tokenStore.saveTokens(server.id, StoredToken(accessToken = accessToken))
            try {
                sessionStore.save(
                    JellyfinSession(
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
            logger.i(LogTag.AUTH, "Jellyfin 登录成功 serverId=${server.id} remoteServerId=$remoteServerId")
            AuthResult.Success(
                MediaUser(serverId = server.id, userId = userId, displayName = userName.orEmpty())
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消红线：绝不折叠成业务异常（ADR-039 §错误与取消语义）
            throw e
        } catch (e: Exception) {
            mapLoginFailure(e)
        }
    }

    /** Jellyfin 无 refresh-token 流程（本阶段），如实返回未实现。 */
    override suspend fun refreshSession(): AuthResult =
        AuthResult.Failure(ProviderException.NotYetImplemented(server.id, "Jellyfin 无 refresh-token 流程"))

    /**
     * 会话恢复 + 验证（App 启动 / 页面进入时调用）。
     *
     * 顺序（防串服，与 Emby 同一纪律）：
     * 1. 本地 Token / Session 缺失 → SignedOut；
     * 2. **无 Token** GET /System/Info/Public → 当前服务器 remoteServerId；
     * 3. 与 session.remoteServerId 不一致 → SERVER_MISMATCH（绝不发送 Token）；
     * 4. 一致 → GET /Users/{userId}（Authorization 内嵌 Token）验证。
     */
    override suspend fun restoreSession(): AuthSessionState {
        val tokens = tokenStore.readTokens(server.id) ?: return AuthSessionState.SignedOut
        val session = sessionStore.read(server.id) ?: return AuthSessionState.SignedOut

        // 服务器身份校验：无 Token 请求，绝不对错误服务器发送旧 Token
        val currentRemoteServerId = try {
            api.getSystemInfoPublic().id
        } catch (e: ApiException) {
            return authErrorFromHttp(e, preserveSession = true)
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

        if (currentRemoteServerId.isNullOrBlank()) {
            return AuthSessionState.Error(
                AuthSessionErrorKind.INVALID_RESPONSE,
                "服务器未返回有效身份标识",
            )
        }
        if (currentRemoteServerId != session.remoteServerId) {
            logger.w(
                LogTag.AUTH,
                "Jellyfin 服务器身份变更 serverId=${server.id} saved=${session.remoteServerId} current=$currentRemoteServerId",
            )
            return AuthSessionState.Error(
                AuthSessionErrorKind.SERVER_MISMATCH,
                "服务器身份已变更，请重新登录",
            )
        }

        return try {
            val user = api.getCurrentUser(tokens.accessToken, session.userId)
            AuthSessionState.Authenticated(
                MediaUser(serverId = server.id, userId = session.userId, displayName = user.name.orEmpty())
            )
        } catch (e: ApiException) {
            // contract §2.3 冻结：401/403 均清本地会话（对 Emby 仅 401 清的有意分歧，ADR-039）
            authErrorFromHttp(e, preserveSession = e.statusCode != 401 && e.statusCode != 403)
        } catch (e: SerializationException) {
            // 协议异常 ≠ 认证失效：保留会话
            AuthSessionState.Error(AuthSessionErrorKind.INVALID_RESPONSE, "服务器响应异常，请稍后重试")
        } catch (e: IOException) {
            AuthSessionState.Error(networkKind(e), "无法连接服务器，请稍后重试")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消红线：绝不折叠成业务异常
            throw e
        } catch (e: Exception) {
            AuthSessionState.Error(AuthSessionErrorKind.UNKNOWN, "验证失败：${e.message}")
        }
    }

    override suspend fun logout() {
        val tokens = tokenStore.readTokens(server.id)
        val session = sessionStore.read(server.id)
        if (tokens != null && session != null) {
            // 服务端撤销：best-effort，且仅当服务器身份一致（防把旧 Token 发给错误服务器）
            val serverIdMatches = try {
                api.getSystemInfoPublic().id
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } == session.remoteServerId
            if (serverIdMatches) {
                try {
                    api.logout(tokens.accessToken)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(LogTag.AUTH, "Jellyfin 服务端登出失败（best-effort） serverId=${server.id}", e)
                }
            }
        }
        // 本地清理：authoritative
        tokenStore.clear(server.id)
        sessionStore.clear(server.id)
        logger.i(LogTag.AUTH, "Jellyfin 已登出 serverId=${server.id}")
    }

    override suspend fun currentUser(): MediaUser? {
        val session = sessionStore.read(server.id) ?: return null
        return MediaUser(
            serverId = server.id,
            userId = session.userId,
            displayName = session.userName,
        )
    }

    private suspend fun clearLocalSession() {
        tokenStore.clear(server.id)
        sessionStore.clear(server.id)
    }

    /** HTTP 错误 → 会话状态；[preserveSession] 为 false 时清理本地会话。 */
    private suspend fun authErrorFromHttp(
        e: ApiException,
        preserveSession: Boolean,
    ): AuthSessionState {
        if (!preserveSession) clearLocalSession()
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
