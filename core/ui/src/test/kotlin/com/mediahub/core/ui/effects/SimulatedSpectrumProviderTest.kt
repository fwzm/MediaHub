package com.mediahub.core.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedSpectrumProviderTest {

    private val provider = SimulatedSpectrumProvider()

    @Test
    fun `all bands stay within bounds over one minute`() {
        var t = 0.0
        while (t < 60.0) {
            val frame = provider.sample(t)
            assertTrue(frame.bass in 0f..1f)
            assertTrue(frame.mid in 0f..1f)
            assertTrue(frame.treble in 0f..1f)
            assertTrue(frame.amplitude in 0f..1f)
            t += 1.0 / 30.0
        }
    }

    @Test
    fun `deterministic for the same timestamp`() {
        val a = provider.sample(12.345)
        val b = provider.sample(12.345)
        assertEquals(a, b)
    }

    @Test
    fun `bass peaks on the beat and decays between beats`() {
        val onBeat = provider.sample(0.0).bass
        val midBeat = provider.sample(0.3).bass
        assertTrue("onBeat=$onBeat midBeat=$midBeat", onBeat > 0.6f)
        assertTrue("onBeat=$onBeat midBeat=$midBeat", onBeat > midBeat * 3f)
    }

    @Test
    fun `noop provider always returns zero`() {
        assertEquals(SpectrumFrame.Zero, SpectrumProvider.Noop.sample(123.0))
    }
}
