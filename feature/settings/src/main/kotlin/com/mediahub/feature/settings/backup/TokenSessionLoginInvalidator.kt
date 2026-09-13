package com.mediahub.feature.settings.backup

import com.mediahub.core.security.TokenStore
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.provider.api.SessionStoreCleaner
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 恢复登录态隔离的生产实现（Phase 1I review）：按 serverId 清除 Token 与
 * 全部 Provider 会话元数据——与 RemoveServerUseCase（ADR-039）同一组合语义，
 * 经 [SessionStoreCleaner] 抽象，不枚举具体 Provider。
 */
class TokenSessionLoginInvalidator @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStoreCleaner: SessionStoreCleaner,
    private val credentialVault: CredentialVault,
    private val accountRepository: AccountRepository,
) : RestoreLoginInvalidator {

    override suspend fun <T> withIdentityChange(serverIds: Set<String>, block: suspend () -> T): T =
        tokenStore.withRestoreIdentityChange(serverIds, block)

    override suspend fun invalidate(serverId: String) = withContext(NonCancellable) {
        // Best effort across each independent store; any failure still aborts the address replacement.
        var failure: Exception? = null
        for (clear in listOf<suspend () -> Unit>(
            { tokenStore.clear(serverId) }, { credentialVault.clear(serverId) },
            { sessionStoreCleaner.clear(serverId) }, { accountRepository.deleteForServer(serverId) },
        )) {
            try { clear() } catch (e: Exception) { if (failure == null) failure = e }
        }
        failure?.let { throw it }
        Unit
    }
}
