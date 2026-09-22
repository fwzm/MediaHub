package com.mediahub.feature.settings.backup

import com.mediahub.core.security.TokenStore
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.provider.api.CredentialGenerationInvalidator
import com.mediahub.provider.api.SessionStoreCleaner
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 恢复登录态隔离的生产实现（Phase 1I review）：按 serverId 清除 Token 与
 * 全部 Provider 会话元数据——与 RemoveServerUseCase（ADR-039）同一组合语义，
 * 经 [SessionStoreCleaner] 抽象，不枚举具体 Provider。
 *
 * 联合候选 A2-5：同时推进全部 [CredentialGenerationInvalidator]（如 WebDAV
 * 凭据协调器）的世代——TokenStore restore lease、CredentialVault 清理与
 * Provider 自有世代机制在同一身份变更事件上显式对齐，互不缺位。
 * 失效为 best-effort：单项失败不阻断其余项，最终汇总抛出（与 invalidate 同策略）。
 */
class TokenSessionLoginInvalidator @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStoreCleaner: SessionStoreCleaner,
    private val credentialVault: CredentialVault,
    private val accountRepository: AccountRepository,
    private val generationInvalidators: Set<@JvmSuppressWildcards CredentialGenerationInvalidator>,
) : RestoreLoginInvalidator {

    override suspend fun <T> withIdentityChange(serverIds: Set<String>, block: suspend () -> T): T {
        advanceGenerationOf(serverIds)
        return tokenStore.withRestoreIdentityChange(serverIds, block)
    }

    override suspend fun invalidate(serverId: String) = withContext(NonCancellable) {
        advanceGenerationOf(setOf(serverId))
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

    /** 全部世代机制失效；单项失败不阻断其余，最后汇总。 */
    private suspend fun advanceGenerationOf(serverIds: Set<String>) {
        var failure: Exception? = null
        for (invalidator in generationInvalidators) {
            for (serverId in serverIds) {
                try {
                    invalidator.invalidateCredentialsGeneration(serverId)
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
            }
        }
        failure?.let { throw it }
    }
}
