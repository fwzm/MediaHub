package com.mediahub.core.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSpectrumProcessorTest {

    @Test
    fun `providers are UI-smoothed by default and engine adapters opt out explicitly`() {
        val defaultProvider = SpectrumProvider { SpectrumFrame.Zero }
        val engineProvider = SpectrumProvider.alreadySmoothed { SpectrumFrame.Zero }

        assertFalse(defaultProvider.isSmoothed)
        assertFalse(SpectrumProvider.Noop.isSmoothed)
        assertFalse(SimulatedSpectrumProvider().isSmoothed)
        assertTrue(engineProvider.isSmoothed)
    }

    @Test
    fun `already-smoothed provider bypasses both attack and release EMA`() {
        var level = 1f
        val provider = SpectrumProvider.alreadySmoothed {
            SpectrumFrame(level, level, level, amplitude = level)
        }
        val processor = RendererSpectrumProcessor()

        val attack = processor.process(provider, timeSec = 0.0, audioGain = 1f, dtSec = FRAME_SEC)
        assertEquals(1f, attack.bass, 0f)
        assertEquals(1f, attack.mid, 0f)
        assertEquals(1f, attack.treble, 0f)
        assertEquals(1f, attack.amplitude, 0f)

        level = 0f
        val release = processor.process(provider, timeSec = 0.1, audioGain = 1f, dtSec = FRAME_SEC)
        assertEquals(SpectrumFrame.Zero, release)
    }

    @Test
    fun `default provider retains UI attack and release smoothing`() {
        var level = 1f
        val provider = SpectrumProvider {
            SpectrumFrame(level, level, level, amplitude = level)
        }
        val processor = RendererSpectrumProcessor()

        val attack = processor.process(provider, timeSec = 0.0, audioGain = 1f, dtSec = FRAME_SEC)
        assertTrue(attack.bass > 0f)
        assertTrue(attack.bass < 1f)

        level = 0f
        val release = processor.process(provider, timeSec = 0.1, audioGain = 1f, dtSec = FRAME_SEC)
        assertTrue(release.bass > 0f)
        assertTrue(release.amplitude > 0f)
    }

    @Test
    fun `bypass path still sanitizes clamps and applies gain to every channel`() {
        val provider = SpectrumProvider.alreadySmoothed {
            SpectrumFrame(
                bass = 0.75f,
                mid = Float.NaN,
                treble = -0.4f,
                amplitude = Float.POSITIVE_INFINITY,
            )
        }
        val processor = RendererSpectrumProcessor()

        val prepared = processor.process(provider, timeSec = 0.0, audioGain = 2f, dtSec = FRAME_SEC)

        assertEquals(1f, prepared.bass, 0f)
        assertEquals(0f, prepared.mid, 0f)
        assertEquals(0f, prepared.treble, 0f)
        assertEquals(0f, prepared.amplitude, 0f)
    }

    @Test
    fun `nonfinite gain fails closed before reaching the renderer`() {
        val provider = SpectrumProvider.alreadySmoothed {
            SpectrumFrame(1f, 1f, 1f, amplitude = 1f)
        }

        val prepared = RendererSpectrumProcessor().process(
            provider = provider,
            timeSec = 0.0,
            audioGain = Float.NaN,
            dtSec = FRAME_SEC,
        )

        assertEquals(SpectrumFrame.Zero, prepared)
    }

    private companion object {
        const val FRAME_SEC = 1f / 60f
    }
}
