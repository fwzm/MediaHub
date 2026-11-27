package com.mediahub.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleBackExitControllerTest {

    private var now = 0L
    private val controller = DoubleBackExitController(windowMs = 2_000L, clock = { now })

    @Test
    fun `the first back only arms the exit window`() {
        assertFalse(controller.onBack())
    }

    @Test
    fun `a second back inside the window exits`() {
        assertFalse(controller.onBack())
        now = 1_999L

        assertTrue(controller.onBack())
    }

    @Test
    fun `a second back after the window re-arms instead of exiting`() {
        assertFalse(controller.onBack())
        now = 2_001L
        assertFalse(controller.onBack())

        now = 3_000L
        assertTrue(controller.onBack())
    }

    @Test
    fun `exiting resets the window so the next back arms again`() {
        assertFalse(controller.onBack())
        now = 500L
        assertTrue(controller.onBack())

        now = 600L
        assertFalse(controller.onBack())
    }

    @Test
    fun `reset discards an armed window`() {
        assertFalse(controller.onBack())
        controller.reset()

        now = 100L
        assertFalse(controller.onBack())
    }
}
