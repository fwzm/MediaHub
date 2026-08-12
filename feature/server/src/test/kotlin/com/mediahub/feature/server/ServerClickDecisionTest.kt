package com.mediahub.feature.server

import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.ProviderCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页卡片点击决策（评审 Patch 3）。 */
class ServerClickDecisionTest {

    private val auth = AuthenticationState.Authenticated(MediaUser("s1", "u1", "a"))

    @Test
    fun `local invalid reauthorizes`() {
        assertEquals(
            ServerClickDecision.Target.LocalReauthorize,
            ServerClickDecision.decide(ProviderCategory.LOCAL_STORAGE, true, null, false),
        )
    }

    @Test
    fun `auth provider signed out relogins`() {
        assertEquals(
            ServerClickDecision.Target.AuthRelogin,
            ServerClickDecision.decide(ProviderCategory.MEDIA_SERVER, false, AuthenticationState.SignedOut, true),
        )
    }

    @Test
    fun `auth provider session expired relogins`() {
        assertEquals(
            ServerClickDecision.Target.AuthRelogin,
            ServerClickDecision.decide(
                ProviderCategory.MEDIA_SERVER, false,
                AuthenticationState.SessionExpired(com.mediahub.provider.api.ProviderException.AuthExpired("s1")), true,
            ),
        )
    }

    @Test
    fun `auth provider unavailable opens not relogin`() {
        assertEquals(
            ServerClickDecision.Target.Open,
            ServerClickDecision.decide(
                ProviderCategory.MEDIA_SERVER, false,
                AuthenticationState.Unavailable(com.mediahub.provider.api.ProviderException.Network("s1"), null), true,
            ),
        )
    }

    @Test
    fun `authenticated opens`() {
        assertEquals(
            ServerClickDecision.Target.Open,
            ServerClickDecision.decide(ProviderCategory.MEDIA_SERVER, false, auth, true),
        )
    }

    @Test
    fun `non-auth provider signed out opens`() {
        // 非认证 Provider（如 Local 已授权）不显示/不进 re-login
        assertEquals(
            ServerClickDecision.Target.Open,
            ServerClickDecision.decide(ProviderCategory.LOCAL_STORAGE, false, AuthenticationState.SignedOut, false),
        )
    }

    @Test
    fun `local valid opens`() {
        assertEquals(
            ServerClickDecision.Target.Open,
            ServerClickDecision.decide(ProviderCategory.LOCAL_STORAGE, false, null, false),
        )
    }
}
