package com.mediahub.core.ui.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaBandSmootherTest {

    @Test
    fun `attack rises faster than release falls for the same delta`() {
        val rising = EmaBandSmoother()
        val falling = EmaBandSmoother()
        repeat(200) { falling.process(1f, FRAME_SEC) }

        val rise = rising.process(1f, FRAME_SEC)
        val fall = falling.process(0f, FRAME_SEC)

        assertTrue("rise=$rise fall-remaining=${1f - fall}", rise > (1f - fall))
    }

    @Test
    fun `converges to target after sustained input`() {
        val smoother = EmaBandSmoother()
        var value = 0f
        repeat(120) { value = smoother.process(0.5f, FRAME_SEC) }
        assertEquals(0.5f, value, 0.01f)
    }

    @Test
    fun `clamps out-of-range targets and dt`() {
        val smoother = EmaBandSmoother()
        val value = smoother.process(target = 5f, dtSec = 100f)
        assertTrue(value in 0f..1f)
        val afterNegative = smoother.process(target = -3f, dtSec = -1f)
        assertTrue(afterNegative in 0f..1f)
    }

    @Test
    fun `reset returns to zero`() {
        val smoother = EmaBandSmoother()
        repeat(60) { smoother.process(1f, FRAME_SEC) }
        smoother.reset()
        assertEquals(0f, smoother.process(0f, FRAME_SEC), 1e-6f)
    }

    @Test
    fun `smoothed spectrum smooths all bands and amplitude`() {
        val smoothed = SmoothedSpectrum()
        var frame = SpectrumFrame.Zero
        repeat(120) { frame = smoothed.process(SpectrumFrame(1f, 1f, 1f), FRAME_SEC) }
        assertEquals(1f, frame.bass, 0.01f)
        assertEquals(1f, frame.mid, 0.01f)
        assertEquals(1f, frame.treble, 0.01f)
        assertEquals(1f, frame.amplitude, 0.01f)
    }

    @Test
    fun `explicit amplitude is smoothed independently from silent bands`() {
        val smoothed = SmoothedSpectrum()
        val frame = smoothed.process(
            SpectrumFrame(bass = 0f, mid = 0f, treble = 0f, amplitude = 1f),
            FRAME_SEC,
        )

        assertEquals(0f, frame.bass, 0f)
        assertEquals(0f, frame.mid, 0f)
        assertEquals(0f, frame.treble, 0f)
        assertTrue(frame.amplitude > 0f)
    }

    @Test
    fun `legacy construction derives amplitude while explicit amplitude wins`() {
        assertEquals(0.5f, SpectrumFrame(1f, 0f, 0f).amplitude, 0f)
        assertEquals(
            0.9f,
            SpectrumFrame(bass = 0f, mid = 0f, treble = 0f, amplitude = 0.9f).amplitude,
            0f,
        )
    }

    @Test
    fun `reset clears independently smoothed amplitude`() {
        val smoothed = SmoothedSpectrum()
        repeat(60) {
            smoothed.process(SpectrumFrame(0f, 0f, 0f, amplitude = 1f), FRAME_SEC)
        }
        smoothed.reset()

        assertEquals(0f, smoothed.process(SpectrumFrame.Zero, FRAME_SEC).amplitude, 1e-6f)
    }

    private companion object {
        const val FRAME_SEC = 1f / 60f
    }
}
