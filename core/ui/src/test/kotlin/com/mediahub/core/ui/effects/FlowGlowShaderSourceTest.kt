package com.mediahub.core.ui.effects

import org.junit.Assert.assertTrue
import org.junit.Test

class FlowGlowShaderSourceTest {

    @Test
    fun `amplitude is declared and used for restrained global modulation`() {
        val source = FlowGlowShader.SOURCE

        assertTrue(source.contains("uniform float uAmplitude;"))
        assertTrue(source.contains("clamp(uAmplitude, 0.0, 1.0)"))
        assertTrue(source.contains("col *= amplitudeGain;"))
    }

    @Test
    fun `treble drives both dispersion and iridescent highlight`() {
        val source = FlowGlowShader.SOURCE

        assertTrue(source.contains("float trebleEnergy = clamp(uTreble, 0.0, 1.0);"))
        assertTrue(source.contains("dispersionOffset = 0.08 + trebleEnergy * 0.04"))
        assertTrue(source.contains("dispersionGain = 0.85 + trebleEnergy * 0.30"))
        assertTrue(source.contains("uIridescence * (0.18 + trebleEnergy * 0.08)"))
    }
}
