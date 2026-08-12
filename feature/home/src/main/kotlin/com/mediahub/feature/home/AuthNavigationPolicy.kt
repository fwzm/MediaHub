package com.mediahub.feature.home

import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState

/**
 * 首页"卡片点击是否进入重新登录"的唯一判定 policy（评审 FINAL PATCH 3）。
 *
 * 生产 HomeViewModel 必须调用它（不能各自复制一份 when）。
 * 精确语义：仅 SignedOut / SESSION_EXPIRED / SERVER_MISMATCH 进入重登录；
 * FORBIDDEN / NETWORK / 5xx / INVALID_RESPONSE / UNKNOWN 保留 session（不送重登录页）。
 */
object AuthNavigationPolicy {

    /**
     * @param supportsAuth 认证型 Provider 才参与判定（Local 等非认证 Provider 恒 false）。
     */
    fun needsRelogin(supportsAuth: Boolean, authState: AuthSessionState?): Boolean {
        if (!supportsAuth) return false
        return when (authState) {
            is AuthSessionState.Authenticated -> false
            is AuthSessionState.Error -> when (authState.kind) {
                AuthSessionErrorKind.SESSION_EXPIRED,
                AuthSessionErrorKind.SERVER_MISMATCH,
                -> true

                AuthSessionErrorKind.FORBIDDEN,
                AuthSessionErrorKind.NETWORK_TIMEOUT,
                AuthSessionErrorKind.NETWORK_UNAVAILABLE,
                AuthSessionErrorKind.SERVER_ERROR,
                AuthSessionErrorKind.INVALID_RESPONSE,
                AuthSessionErrorKind.UNKNOWN,
                -> false
            }

            AuthSessionState.SignedOut -> true
            else -> false // Unknown / Restoring
        }
    }
}
