package com.mediahub.feature.home

import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthNavigationPolicy：首页"是否进入重新登录"的判定（生产 HomeViewModel 实际调用）。
 * 精确语义：仅 SignedOut / SESSION_EXPIRED / SERVER_MISMATCH 进入重登录；
 * FORBIDDEN / NETWORK / 5xx / INVALID_RESPONSE / UNKNOWN 保留 session。
 */
class AuthNavigationPolicyTest {

    private val error = { kind: AuthSessionErrorKind -> AuthSessionState.Error(kind, "x") }

    @Test
    fun `signed out relogs in`() {
        assertTrue(AuthNavigationPolicy.needsRelogin(supportsAuth = true, AuthSessionState.SignedOut))
    }

    @Test
    fun `session expired and mismatch relogin`() {
        assertTrue(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.SESSION_EXPIRED)))
        assertTrue(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.SERVER_MISMATCH)))
    }

    @Test
    fun `transient errors and authenticated do not relogin`() {
        assertFalse(AuthNavigationPolicy.needsRelogin(
            true,
            AuthSessionState.Authenticated(com.mediahub.model.MediaUser("s1", "u1", "a")),
        ))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, AuthSessionState.Unknown))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, AuthSessionState.Restoring))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.FORBIDDEN)))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.NETWORK_TIMEOUT)))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.NETWORK_UNAVAILABLE)))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.SERVER_ERROR)))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.INVALID_RESPONSE)))
        assertFalse(AuthNavigationPolicy.needsRelogin(true, error(AuthSessionErrorKind.UNKNOWN)))
    }

    @Test
    fun `non-auth provider never relogs in`() {
        assertFalse(AuthNavigationPolicy.needsRelogin(supportsAuth = false, AuthSessionState.SignedOut))
        assertFalse(AuthNavigationPolicy.needsRelogin(false, error(AuthSessionErrorKind.SESSION_EXPIRED)))
    }
}
