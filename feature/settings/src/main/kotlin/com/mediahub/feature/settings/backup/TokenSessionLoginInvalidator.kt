package com.mediahub.feature.settings.backup

import com.mediahub.core.security.TokenStore
import com.mediahub.provider.api.SessionStoreCleaner
import javax.inject.Inject

/**
 * 恢复登录态隔离的生产实现（Phase 1I review）：按 serverId 清除 Token 与
 * 全部 Provider 会话元数据——与 RemoveServerUseCase（ADR-039）同一组合语义，
 * 经 [SessionStoreCleaner] 抽象，不枚举具体 Provider。
 */
class TokenSessionLoginInvalidator @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStoreCleaner: SessionStoreCleaner,
) : RestoreLoginInvalidator {

    override suspend fun invalidate(serverId: String) {
        tokenStore.clear(serverId)
        sessionStoreCleaner.clear(serverId)
    }
}
