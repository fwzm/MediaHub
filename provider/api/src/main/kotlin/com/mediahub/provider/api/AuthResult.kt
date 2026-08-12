package com.mediahub.provider.api

import com.mediahub.model.MediaUser

/** 认证结果。 */
sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult {
        val user: MediaUser get() = session.user
    }
    data class Failure(val error: ProviderException) : AuthResult
}

/** Provider 对已持久化会话的验证结果；只有 [Invalidated] 允许协调器删除会话。 */
sealed interface SessionRestoreResult {
    data class Restored(val session: AuthSession) : SessionRestoreResult
    data class Invalidated(val error: ProviderException) : SessionRestoreResult
    data class Unavailable(val error: ProviderException) : SessionRestoreResult
}

/** App/Feature 可消费的通用认证状态，不泄露 SessionCredential。 */
sealed interface AuthenticationState {
    data object SignedOut : AuthenticationState
    data class Authenticated(val user: MediaUser) : AuthenticationState
    data class SessionExpired(val error: ProviderException) : AuthenticationState
    data class Unavailable(val error: ProviderException, val user: MediaUser?) : AuthenticationState
}
