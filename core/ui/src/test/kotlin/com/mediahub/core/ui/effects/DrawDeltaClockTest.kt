package com.mediahub.core.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the second PR #10 finding: the spectrum smoother used the nominal `1f / fps` as its
 * step, so smoothing ran at the clock's rate instead of the rate the display actually drew.
 */
class DrawDeltaClockTest {

    @Test
    fun `first draw uses the caller fallback because there is no previous timestamp`() {
        val clock = DrawDeltaClock(nanos = { 1_000_000_000L })
        assertEquals(0.0166f, clock.nextSec(fallbackSec = 0.0166f), 0f)
    }

    @Test
    fun `subsequent draws report the real elapsed seconds`() {
        var now = 1_000_000_000L
        val clock = DrawDeltaClock(nanos = { now })

        clock.nextSec(fallbackSec = 0.02f)
        now += 16_700_000
        assertEquals(0.0167f, clock.nextSec(fallbackSec = 0.02f), 1e-4f)
        now += 8_300_000
        assertEquals(0.0083f, clock.nextSec(fallbackSec = 0.02f), 1e-4f)
    }

    @Test
    fun `a long stall is capped instead of being trusted`() {
        var now = 1_000_000_000L
        val clock = DrawDeltaClock(nanos = { now })

        clock.nextSec(fallbackSec = 0.02f)
        now += 5_000_000_000L
        assertEquals(DrawDeltaClock.MAX_DELTA_SEC, clock.nextSec(fallbackSec = 0.02f), 0f)
    }

    @Test
    fun `a backwards timestamp falls back instead of moving smoothing backwards`() {
        var now = 1_000_000_000L
        val clock = DrawDeltaClock(nanos = { now })

        clock.nextSec(fallbackSec = 0.02f)
        now -= 5_000_000
        assertEquals(0.02f, clock.nextSec(fallbackSec = 0.02f), 0f)
    }
}
