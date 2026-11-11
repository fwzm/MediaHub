package com.mediahub.feature.settings.sync

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.security.CredentialVault
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaProviderRegistry

/**
 * 生产凭据端口：落盘只走 core/security 的 [CredentialVault]（Keystore 加密），
 * 不写 Room、不写 DataStore、不进日志。
 *
 * 用 [CredentialVault.CredentialKind.PASSWORD] 这一个键：W6-H 的"补录并验证"就是给来源补密码，
 * 而 Emby/Jellyfin 正常登录路径只存 Token（TokenStore），两者互不覆盖。
 */
class VaultSourceCredentialPort(
    private val vault: CredentialVault,
) : SourceCredentialPort {

    override suspend fun hasSecret(serverId: String): Boolean =
        vault.contains(serverId, CredentialVault.CredentialKind.PASSWORD)

    override suspend fun readSecret(serverId: String): String? =
        vault.read(serverId, CredentialVault.CredentialKind.PASSWORD)

    override suspend fun saveSecret(serverId: String, secret: String) {
        vault.save(serverId, CredentialVault.CredentialKind.PASSWORD, secret)
    }
}

/**
 * 生产校验端口：把一次"补录并验证"变成 provider 层的一次真实认证调用。
 *
 * 复刻的是既有登录路径（ProviderHandle.auth.authenticate(Credentials.UsernamePassword)），
 * 没有新写任何 HTTP；因此结论与"在服务器管理里登录"一致。
 * 失败原因取 [com.mediahub.provider.api.ProviderException] 的 message（core 层已消毒，不含密码），
 * 协调器还会再脱敏一次。
 */
class ProviderSourceAuthenticator(
    private val servers: ServerStore,
    private val registry: MediaProviderRegistry,
) : SourceAuthenticator {

    override suspend fun authenticate(
        source: SourceVerificationSource,
        username: String?,
        secret: String,
    ): SourceAuthVerdict {
        val server = servers.getServer(source.serverId)
            ?: return failed(
                SourceVerificationReasons.CREDENTIALS_UNAVAILABLE,
                MISSING_SERVER_MESSAGE,
            )
        val handle = registry.create(server)
            ?: return failed(
                SourceVerificationReasons.AUTH_NOT_SUPPORTED,
                SourceVerificationCopy.NOT_SUPPORTED_MESSAGE,
            )
        val auth = handle.auth
            ?: return failed(
                SourceVerificationReasons.AUTH_NOT_SUPPORTED,
                SourceVerificationCopy.NOT_SUPPORTED_MESSAGE,
            )
        val account = username?.trim()?.takeIf { it.isNotEmpty() }
            ?: server.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failed(
                SourceVerificationReasons.MISSING_ACCOUNT,
                SourceVerificationCopy.MISSING_ACCOUNT_MESSAGE,
            )
        return when (val result = auth.authenticate(Credentials.UsernamePassword(account, secret))) {
            is AuthResult.Success -> SourceAuthVerdict(
                verified = true,
                reason = SourceVerificationReasons.VERIFIED,
                message = null,
            )

            is AuthResult.Failure -> SourceAuthVerdict(
                verified = false,
                reason = SourceVerificationReasons.AUTH_FAILED,
                message = result.error.message,
            )
        }
    }

    private fun failed(reason: String, message: String): SourceAuthVerdict =
        SourceAuthVerdict(verified = false, reason = reason, message = message)

    private companion object {
        const val MISSING_SERVER_MESSAGE: String = "本机找不到这条来源"
    }
}

