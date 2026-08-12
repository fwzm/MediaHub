package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthenticationCoordinator
import com.mediahub.provider.api.AuthenticationDisposition
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.SessionRestoreResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAuthenticationCoordinator @Inject constructor(
    private val credentialVault: CredentialVault,
    private val logger: Logger,
) : AuthenticationCoordinator {

    override suspend fun authenticateOrDefer(
        handle: ProviderHandle,
        credentials: Credentials,
    ): AuthenticationDisposition {
        val auth = handle.auth
        if (auth == null) {
            if (handle.descriptor.authMethod == com.mediahub.provider.api.AuthMethod.NONE) {
                throw ProviderException.AuthFailed(handle.provider.serverId, "该 Provider 不支持认证")
            }
            requireDeferrable(handle.provider.serverId, credentials)
            credentialVault.savePending(handle.provider.serverId, credentials)
            logger.i(LogTag.AUTH, "认证能力尚未接入，凭据已加密暂存 serverId=${handle.provider.serverId}")
            return AuthenticationDisposition.DeferredUntilProviderImplementation
        }
        return try {
            when (val result = auth.authenticate(credentials)) {
                is AuthResult.Success -> {
                    credentialVault.saveSession(handle.provider.serverId, result.session)
                    credentialVault.clearPending(handle.provider.serverId)
                    logger.i(LogTag.AUTH, "认证会话已加密保存 serverId=${handle.provider.serverId}")
                    AuthenticationDisposition.Authenticated(result)
                }

                is AuthResult.Failure -> throw result.error
            }
        } catch (e: ProviderException.NotYetImplemented) {
            requireDeferrable(handle.provider.serverId, credentials)
            credentialVault.savePending(handle.provider.serverId, credentials)
            logger.i(LogTag.AUTH, "认证凭据已加密暂存 serverId=${handle.provider.serverId}")
            AuthenticationDisposition.DeferredUntilProviderImplementation
        }
    }

    override suspend fun restore(handle: ProviderHandle): AuthenticationState {
        val serverId = handle.provider.serverId
        val session = credentialVault.readSession(serverId)
            ?: return AuthenticationState.SignedOut
        val auth = handle.auth ?: return AuthenticationState.Unavailable(
            ProviderException.NotYetImplemented(serverId, "会话恢复"),
            session.user,
        )

        return try {
            when (val result = auth.restoreSession(session)) {
                is SessionRestoreResult.Restored -> {
                    if (result.session != session) credentialVault.saveSession(serverId, result.session)
                    logger.i(LogTag.AUTH, "认证会话恢复成功 serverId=$serverId")
                    AuthenticationState.Authenticated(result.session.user)
                }

                is SessionRestoreResult.Invalidated -> {
                    credentialVault.clear(serverId)
                    logger.i(LogTag.AUTH, "认证会话已确认失效并清理 serverId=$serverId")
                    AuthenticationState.SessionExpired(result.error)
                }

                is SessionRestoreResult.Unavailable -> AuthenticationState.Unavailable(
                    result.error,
                    session.user,
                )
            }
        } catch (e: ProviderException) {
            // 未明确确认失效的协议/网络/解析错误一律保留会话。
            AuthenticationState.Unavailable(e, session.user)
        } catch (e: Exception) {
            AuthenticationState.Unavailable(ProviderException.Unknown(serverId, e), session.user)
        }
    }

    override suspend fun logout(handle: ProviderHandle) {
        val serverId = handle.provider.serverId
        val session = credentialVault.readSession(serverId)
        try {
            if (session != null) {
                runCatching { handle.auth?.logout(session) }
                    .onFailure { logger.w(LogTag.AUTH, "服务端登出失败（best-effort） serverId=$serverId", it) }
            }
        } finally {
            credentialVault.clear(serverId)
            logger.i(LogTag.AUTH, "本地认证会话已清理 serverId=$serverId")
        }
    }

    override suspend fun clear(serverId: String) = credentialVault.clear(serverId)

    private fun requireDeferrable(serverId: String, credentials: Credentials) {
        if (credentials is Credentials.UsernamePassword) {
            throw ProviderException.NotYetImplemented(serverId, "用户名密码登录")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthenticationCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindAuthenticationCoordinator(
        implementation: DefaultAuthenticationCoordinator,
    ): AuthenticationCoordinator
}
