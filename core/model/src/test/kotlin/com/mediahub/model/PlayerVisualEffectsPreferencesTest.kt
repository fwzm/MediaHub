package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVisualEffectsPreferencesTest {

    @Test
    fun `defaults are discoverable and restrained`() {
        val preferences = PlayerVisualEffectsPreferences()

        assertTrue(preferences.enabled)
        assertEquals(PlayerVisualPreset.AURORA, preferences.preset)
        assertEquals(0.35f, preferences.intensity, 0f)
        assertTrue(preferences.followArtworkColors)
        assertTrue(preferences.audioReactive)
        assertEquals(VisualPerformanceMode.AUTO, preferences.performanceMode)
        assertTrue(preferences.isEffectivelyEnabled)
    }

    @Test
    fun `disabled switch stops rendering while retaining last preset`() {
        val disabled = PlayerVisualEffectsPreferences(
            enabled = false,
            preset = PlayerVisualPreset.LIQUID,
        )

        assertFalse(disabled.isEffectivelyEnabled)
        assertEquals(PlayerVisualPreset.LIQUID, disabled.preset)
    }

    @Test
    fun `preset enum contains only renderable choices`() {
        assertEquals(
            listOf(
                PlayerVisualPreset.AURORA,
                PlayerVisualPreset.LIQUID,
                PlayerVisualPreset.SPECTRUM,
            ),
            PlayerVisualPreset.entries.toList(),
        )
    }

    @Test
    fun `normalization clamps finite and infinite intensity`() {
        assertEquals(
            PlayerVisualEffectsPreferences.MIN_INTENSITY,
            PlayerVisualEffectsPreferences(intensity = -2f).normalized().intensity,
            0f,
        )
        assertEquals(
            PlayerVisualEffectsPreferences.MAX_INTENSITY,
            PlayerVisualEffectsPreferences(intensity = 4f).normalized().intensity,
            0f,
        )
        assertEquals(
            PlayerVisualEffectsPreferences.MIN_INTENSITY,
            PlayerVisualEffectsPreferences(intensity = Float.NEGATIVE_INFINITY).normalized().intensity,
            0f,
        )
        assertEquals(
            PlayerVisualEffectsPreferences.MAX_INTENSITY,
            PlayerVisualEffectsPreferences(intensity = Float.POSITIVE_INFINITY).normalized().intensity,
            0f,
        )
    }

    @Test
    fun `normalization replaces NaN and does not copy valid value`() {
        assertEquals(
            PlayerVisualEffectsPreferences.DEFAULT_INTENSITY,
            PlayerVisualEffectsPreferences(intensity = Float.NaN).normalized().intensity,
            0f,
        )

        val valid = PlayerVisualEffectsPreferences(intensity = 0.72f)
        assertSame(valid, valid.normalized())
    }
}
