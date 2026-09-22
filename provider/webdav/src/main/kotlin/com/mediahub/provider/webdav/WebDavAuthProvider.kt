package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.ProviderException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * WebDAV 只读认证（Basic）。
 *
 * 与 Emby/Jellyfin 的差异：WebDAV 没有服务端签发的会话令牌，
 * 每次请求都携带 Basic 凭据，因此：
 * - 密码进 [com.mediahub.core.security.CredentialVault]，**不写入** TokenStore
 *   （不伪造 access/refresh token；[TokenStore] 只用于 logout 时的会话清理）；
 * - "认证验证" = 对根目录做 `PROPFIND Depth: 0`，要求 2xx；验证通过才落盘凭据；
 * - `restoreSession` 在读不到密码时直接 [AuthSessionState.SignedOut]，
 *   绝不以匿名请求代替已失效会话。
 */
internal class WebDavAuthProvider(
    private val server: MediaServer,
    private val api: WebDavApi,
    private val credentialStore: WebDavCredentialStore,
    private val tokenStore: TokenStore,
    private val logger: Logger,
) : MediaAuthProvider {

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val pair = when (credentials) {
            is Credentials.UsernamePassword -> credentials.username to credentials.password
            is Credentials.WebDav -> credentials.username to credentials.password
            else -> return AuthResult.Failure(
                ProviderException.AuthFailed(server.id, "WebDAV 仅支持用户名密码认证")
            )
        }
        val (username, password) = pair
        if (username.isBlank()) {
            return AuthResult.Failure(ProviderException.AuthFailed(server.id, "用户名不能为空"))
        }

        val header = WebDavAuth.basicHeader(username, password)
        val rootUrl = WebDavUrls.normalizeBase(server.baseUrl)
        if (rootUrl.isBlank()) {
            return AuthResult.Failure(ProviderException.AuthFailed(server.id, "服务器地址为空"))
        }

        // 认证 attempt（A3-2）：网络探测前捕获世代；迟到 2xx 只能条件提交，
        // 不得覆盖较新身份（新登录/登出/删除/restore 失效都会推进世代）。
        val attemptGeneration = credentialStore.beginAuthenticationAttempt(server.id)
        val attemptIdentity = credentialStore.identityOf(server)

        try {
            api.propfind(rootUrl, depth = 0, authorization = header)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException.AuthExpired) {
            logger.i(LogTag.AUTH, "WebDAV 认证失败（401） serverId=${server.id}")
            return AuthResult.Failure(ProviderException.AuthFailed(server.id, "用户名或密码错误"))
        } catch (e: ProviderException) {
            logger.w(LogTag.AUTH, "WebDAV 认证探测失败 serverId=${server.id} code=${e.code}")
            return AuthResult.Failure(e)
        }

        // 只有真实 2xx 验证通过且身份未被取代才落盘凭据（fail-closed）。
        val committed = credentialStore.commitAuthentication(
            serverId = server.id,
            attemptGeneration = attemptGeneration,
            password = password,
            identity = attemptIdentity,
        )
        if (!committed) {
            logger.i(LogTag.AUTH, "WebDAV 登录已被较新身份操作取代，不落盘 serverId=${server.id}")
            return AuthResult.Failure(
                ProviderException.AuthFailed(server.id, "登录已被更新的身份操作取代，请重试")
            )
        }
        logger.i(LogTag.AUTH, "WebDAV 认证成功 serverId=${server.id}")
        return AuthResult.Success(MediaUser(serverId = server.id, userId = username, displayName = username))
    }

    override suspend fun refreshSession(): AuthResult = when (val state = restoreSession()) {
        is AuthSessionState.Authenticated -> AuthResult.Success(state.user)
        is AuthSessionState.Error ->
            AuthResult.Failure(ProviderException.AuthFailed(server.id, state.message))
        else -> AuthResult.Failure(ProviderException.AuthRequired(server.id))
    }

    override suspend fun restoreSession(): AuthSessionState {
        val username = server.username?.takeIf { it.isNotBlank() }
            ?: return AuthSessionState.SignedOut
        // 同身份读取密码 + 捕获世代（A3-2）：身份指纹不符（地址/用户名已替换）
        // 直接视为无凭据，不发出任何网络请求；401 清理凭世代判定，
        // 迟到失败不得清掉较新身份。
        val credential = credentialStore.readPasswordFor(server)
            ?: return AuthSessionState.SignedOut
        val rootUrl = WebDavUrls.normalizeBase(server.baseUrl)
        if (rootUrl.isBlank()) {
            return AuthSessionState.Error(AuthSessionErrorKind.INVALID_RESPONSE, "服务器地址为空")
        }
        return try {
            api.propfind(
                rootUrl,
                depth = 0,
                authorization = WebDavAuth.basicHeader(username, credential.password),
            )
            // 迟到 2xx（A3-2）：响应到达时身份/世代已变 → 不得复活旧用户状态。
            // 世代推进覆盖：较新登录（含同身份重登，旧密码已失效）、登出/删除、
            // restore 失效——一律回 SignedOut 交由上层重新认证。
            if (!credentialStore.isStillCurrent(server, credential)) {
                logger.i(LogTag.AUTH, "WebDAV 恢复结果过期（身份已变化），不发布旧会话 serverId=${server.id}")
                return AuthSessionState.SignedOut
            }
            AuthSessionState.Authenticated(
                MediaUser(serverId = server.id, userId = username, displayName = username)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException.AuthExpired) {
            // 明确认证失效且身份未变（世代一致）才销毁凭据；清理失败不冒充成功，
            // 也不掩盖会话失效结论（密码残留时后续 401 会再次尝试清理）。
            val cleanup = runCatching {
                withContext(NonCancellable) { credentialStore.clearIfStill(server.id, credential) }
            }
            cleanup.exceptionOrNull()?.let {
                logger.w(LogTag.AUTH, "WebDAV 凭据条件清理失败 serverId=${server.id}")
            }
            logger.i(LogTag.AUTH, "WebDAV 会话失效（401），已按世代清理凭据 serverId=${server.id}")
            AuthSessionState.Error(AuthSessionErrorKind.SESSION_EXPIRED, "登录状态已过期，请重新登录")
        } catch (e: ProviderException.NotFound) {
            // 404/410：地址不是有效的 WebDAV 目录（凭据仍有效，保留）。
            AuthSessionState.Error(AuthSessionErrorKind.INVALID_RESPONSE, "地址不是有效的 WebDAV 目录")
        } catch (e: ProviderException.Http) {
            when {
                e.statusCode == 403 -> AuthSessionState.Error(AuthSessionErrorKind.FORBIDDEN, "没有访问权限")
                e.statusCode >= 500 ->
                    AuthSessionState.Error(AuthSessionErrorKind.SERVER_ERROR, "服务器错误（HTTP ${e.statusCode}）")
                else -> AuthSessionState.Error(
                    AuthSessionErrorKind.UNKNOWN,
                    "服务器返回 HTTP ${e.statusCode}",
                )
            }
        } catch (e: ProviderException.Network) {
            AuthSessionState.Error(AuthSessionErrorKind.NETWORK_UNAVAILABLE, "网络不可用，请稍后重试")
        } catch (e: ProviderException.Parse) {
            AuthSessionState.Error(AuthSessionErrorKind.INVALID_RESPONSE, "服务器响应不是有效的 WebDAV 内容")
        } catch (e: ProviderException) {
            AuthSessionState.Error(AuthSessionErrorKind.UNKNOWN, "会话恢复失败")
        }
    }

    override suspend fun logout() {
        credentialStore.clear(server.id)
        tokenStore.clear(server.id)
        logger.i(LogTag.AUTH, "WebDAV 已登出 serverId=${server.id}")
    }

    override suspend fun currentUser(): MediaUser? {
        val username = server.username?.takeIf { it.isNotBlank() } ?: return null
        val password = credentialStore.readPasswordValue(server) ?: return null
        return if (password.isNotEmpty()) {
            MediaUser(serverId = server.id, userId = username, displayName = username)
        } else {
            null
        }
    }
}
