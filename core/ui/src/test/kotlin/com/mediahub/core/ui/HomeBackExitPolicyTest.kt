package com.mediahub.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackExitPolicyTest {

    @Test
    fun `first back shows hint`() {
        assertEquals(HomeBackExitPolicy.Decision.ShowHint, HomeBackExitPolicy.decide(1_000L, null))
    }

    @Test
    fun `second back within window exits`() {
        assertEquals(HomeBackExitPolicy.Decision.Exit, HomeBackExitPolicy.decide(2_500L, 1_000L))
    }

    @Test
    fun `second back at window boundary exits`() {
        assertEquals(HomeBackExitPolicy.Decision.Exit, HomeBackExitPolicy.decide(3_000L, 1_000L))
    }

    @Test
    fun `second back after window shows hint`() {
        assertEquals(HomeBackExitPolicy.Decision.ShowHint, HomeBackExitPolicy.decide(3_001L, 1_000L))
    }

    @Test
    fun `backwards clock shows hint`() {
        assertEquals(HomeBackExitPolicy.Decision.ShowHint, HomeBackExitPolicy.decide(900L, 1_000L))
    }
}
