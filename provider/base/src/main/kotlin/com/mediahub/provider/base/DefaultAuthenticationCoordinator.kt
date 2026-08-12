package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthenticationCoordinator
import com.mediahub.provider.api.AuthenticationDisposition
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
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
            ?: throw ProviderException.AuthFailed(handle.provider.serverId, "该 Provider 不支持认证")
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
            credentialVault.savePending(handle.provider.serverId, credentials)
            logger.i(LogTag.AUTH, "认证凭据已加密暂存 serverId=${handle.provider.serverId}")
            AuthenticationDisposition.DeferredUntilProviderImplementation
        }
    }

    override suspend fun clear(serverId: String) = credentialVault.clear(serverId)
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
