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

        // 只有真实 2xx 验证通过后才落盘凭据（fail-closed）。
        credentialStore.savePassword(server.id, password)
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
        // 捕获密码与其凭据世代：401 清理凭世代判定，迟到失败不得清掉较新身份
        val credential = credentialStore.readPassword(server.id)
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
            AuthSessionState.Authenticated(
                MediaUser(serverId = server.id, userId = username, displayName = username)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException.AuthExpired) {
            // 明确认证失效且身份未变（世代一致）才销毁凭据。
            withContext(NonCancellable) { credentialStore.clearIfStill(server.id, credential) }
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
        val password = credentialStore.readPasswordValue(server.id) ?: return null
        return if (password.isNotEmpty()) {
            MediaUser(serverId = server.id, userId = username, displayName = username)
        } else {
            null
        }
    }
}
