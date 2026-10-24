package com.mediahub.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0-A（ADR-045）：UA 策略的作用域与校验边界。
 *
 * 这里证明的是**策略语义**；实际 HTTP 行为由 [UserAgentInterceptorTest] 用 MockWebServer 证明。
 */
class UserAgentPolicyTest {

    private fun policyOf(vararg pairs: Pair<String, String>) =
        UserAgentPolicy(overrides = { pairs.toMap() })

    @Test
    fun `unknown or blank server keeps the default user agent`() {
        val policy = policyOf("srv-1" to "Custom/1.0")
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer(null))
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer(""))
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer("   "))
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer("srv-unknown"))
    }

    @Test
    fun `custom user agent applies to its own server only`() {
        val policy = policyOf("srv-1" to "Kodi/21.0", "srv-2" to "Infuse/7")
        assertEquals("Kodi/21.0", policy.forServer("srv-1"))
        assertEquals("Infuse/7", policy.forServer("srv-2"))
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer("srv-3"))
    }

    @Test
    fun `blank override means use default`() {
        val policy = policyOf("srv-1" to "   ")
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer("srv-1"))
        assertFalse(policy.hasCustomUserAgent("srv-1"))
    }

    @Test
    fun `crlf injection is rejected and falls back to default`() {
        val policy = policyOf("srv-1" to "Evil/1.0\r\nX-Injected: 1")
        assertEquals(MediaHubUserAgent.DEFAULT, policy.forServer("srv-1"))
    }

    @Test
    fun `control characters are rejected`() {
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to "A\u0000B").forServer("s"))
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to "A\u0007B").forServer("s"))
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to "A\u007FB").forServer("s"))
    }

    @Test
    fun `non ascii is rejected`() {
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to "播放器/1.0").forServer("s"))
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to "Player/1.0 \u00e9").forServer("s"))
    }

    @Test
    fun `overlong user agent is rejected`() {
        val tooLong = "A".repeat(UserAgentValidator.MAX_LENGTH + 1)
        assertEquals(MediaHubUserAgent.DEFAULT, policyOf("s" to tooLong).forServer("s"))
        val atLimit = "A".repeat(UserAgentValidator.MAX_LENGTH)
        assertEquals(atLimit, policyOf("s" to atLimit).forServer("s"))
    }

    @Test
    fun `trimming is applied but inner spaces are preserved`() {
        assertEquals("Kodi/21.0 (Android TV)", policyOf("s" to "  Kodi/21.0 (Android TV)  ").forServer("s"))
    }

    @Test
    fun `has custom user agent reflects only effective config`() {
        val policy = policyOf("ok" to "Good/1.0", "bad" to "Bad\r\nInjected: 1")
        assertTrue(policy.hasCustomUserAgent("ok"))
        assertFalse(policy.hasCustomUserAgent("bad"))
        assertFalse(policy.hasCustomUserAgent(null))
    }

    @Test
    fun `policies do not share state through any global singleton`() {
        val a = policyOf("srv-1" to "A/1")
        val b = policyOf("srv-1" to "B/1")
        assertEquals("A/1", a.forServer("srv-1"))
        assertEquals("B/1", b.forServer("srv-1"))
        assertEquals(MediaHubUserAgent.DEFAULT, UserAgentPolicy().forServer("srv-1"))
    }

    @Test
    fun `validator reports reason for rejection and null for blank`() {
        assertNull(UserAgentValidator.validate("   ").reason)
        assertNull(UserAgentValidator.validate("   ").value)
        assertTrue(UserAgentValidator.validate("Good/1.0").valid)
        assertEquals("Good/1.0", UserAgentValidator.validate("Good/1.0").value)
        assertFalse(UserAgentValidator.validate("bad\r\n").valid)
    }

    @Test
    fun `default user agent is identifiable and versioned`() {
        assertTrue(MediaHubUserAgent.DEFAULT.startsWith(MediaHubUserAgent.PRODUCT))
        assertEquals("MediaHub/2.5 (Android)", MediaHubUserAgent.ofVersion("2.5"))
        assertEquals("MediaHub/2.5 (Android 15)", MediaHubUserAgent.ofVersion("2.5", "15"))
        assertEquals("MediaHub/1.0 (Android)", MediaHubUserAgent.ofVersion(null))
        assertEquals("MediaHub/1.0 (Android)", MediaHubUserAgent.ofVersion("   "))
    }
}
