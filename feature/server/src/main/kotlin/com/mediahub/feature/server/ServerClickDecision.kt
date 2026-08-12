package com.mediahub.feature.server

import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.ProviderCategory

/**
 * 首页卡片点击决策（评审 Final Reconciliation Patch 3）。
 *
 * 纯函数：输入 服务器类别 / 是否需本地重授权 / 当前认证态 / 是否认证型 Provider，
 * 输出点击目标。便于 JVM 单测，且与 UI/ViewModel 解耦。
 */
internal object ServerClickDecision {

    sealed interface Target {
        data object Open : Target
        data object LocalReauthorize : Target
        data object AuthRelogin : Target
    }

    fun decide(
        category: ProviderCategory?,
        requiresLocalReauthorize: Boolean,
        authState: AuthenticationState?,
        isAuthProvider: Boolean,
    ): Target {
        // Local 且需重新授权 → 本地目录授权
        if (category == ProviderCategory.LOCAL_STORAGE && requiresLocalReauthorize) {
            return Target.LocalReauthorize
        }
        if (isAuthProvider) {
            val needsRelogin = when (authState) {
                is AuthenticationState.Authenticated -> false
                is AuthenticationState.SessionExpired -> true // 含 SESSION_EXPIRED / SERVER_MISMATCH
                is AuthenticationState.Unavailable -> false   // 暂不可用不强制重登
                AuthenticationState.SignedOut -> true
                null -> false
            }
            if (needsRelogin) return Target.AuthRelogin
        }
        return Target.Open
    }
}
