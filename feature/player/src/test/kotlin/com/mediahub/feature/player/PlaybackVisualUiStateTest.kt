package com.mediahub.feature.player

import com.mediahub.core.ui.effects.RendererBackend
import com.mediahub.core.ui.effects.SpectrumFrame
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.UserPreferences
import com.mediahub.model.VisualPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackVisualUiStateTest {

    @Test
    fun `renderer remains off until persisted preferences have loaded`() {
        assertFalse(PlaybackVisualStateResolver.preferencesForRenderer(null).enabled)
        assertFalse(
            PlaybackVisualStateResolver.preferencesForRenderer(
                UserPreferences(
                    playerVisualEffects = PlayerVisualEffectsPreferences.Default.copy(enabled = false),
                ),
            ).enabled,
        )
        assertTrue(
            PlaybackVisualStateResolver.preferencesForRenderer(UserPreferences()).enabled,
        )
    }
    @Test
    fun `disabled preference stops renderer and preserves selected preset`() {
        val state = resolve(
            preferences = PlayerVisualEffectsPreferences(
                enabled = false,
                preset = PlayerVisualPreset.LIQUID,
            ),
        )

        assertFalse(state.enabled)
        assertEquals(PlayerVisualPreset.LIQUID, state.preferences.preset)
        assertFalse(state.renderRequest.frameDecision.running)
        assertEquals(0, state.targetFps)
    }

    @Test
    fun `hidden controls and stopped lifecycle are hard zero-fps gates`() {
        assertEquals(0, resolve(controlsVisible = false).targetFps)
        assertEquals(0, resolve(lifecycleStarted = false).targetFps)
    }

    @Test
    fun `auto interaction reaches 60 while power saver clamps to 15`() {
        assertEquals(60, resolve(userInteracting = true).targetFps)
        assertEquals(15, resolve(userInteracting = true, powerSave = true).targetFps)
    }

    @Test
    fun `explicit performance modes map to product frame rates`() {
        assertEquals(15, resolve(mode = VisualPerformanceMode.BATTERY).targetFps)
        assertEquals(30, resolve(mode = VisualPerformanceMode.BALANCED).targetFps)
        assertEquals(60, resolve(mode = VisualPerformanceMode.HIGH).targetFps)
    }

    @Test
    fun `artwork palette is consumed only when follow artwork is enabled`() {
        val artwork = VisualPalette(
            background = 0xFF101820.toInt(),
            primary = 0xFF22AA88.toInt(),
            secondary = 0xFF7755CC.toInt(),
            accent = 0xFFFFCC44.toInt(),
        )
        val followed = resolve(artwork = artwork)
        val ignored = resolve(
            preferences = PlayerVisualEffectsPreferences(followArtworkColors = false),
            artwork = artwork,
        )

        assertSame(artwork, followed.sourcePalette)
        assertTrue(ignored.sourcePalette != artwork)
        assertEquals(followed.sourcePalette, followed.renderRequest.palette)
    }

    @Test
    fun `audio unavailable exposes fallback while available spectrum keeps independent amplitude`() {
        val unavailable = resolve(audio = null)
        assertFalse(unavailable.audioReactiveAvailable)
        assertFalse(unavailable.renderRequest.audioReactive)
        assertEquals(SpectrumFrame.Zero, unavailable.spectrum)

        val available = resolve(
            audio = SpectrumFrame(bass = 0.2f, mid = 0.4f, treble = 0.6f, amplitude = 0.9f),
        )
        assertTrue(available.audioReactiveAvailable)
        assertTrue(available.renderRequest.audioReactive)
        assertEquals(0.9f, available.spectrum.amplitude, 0.0001f)
    }

    @Test
    fun `non-finite audio values are fail-soft sanitized`() {
        val state = resolve(
            audio = SpectrumFrame(
                bass = Float.NaN,
                mid = Float.POSITIVE_INFINITY,
                treble = -1f,
                amplitude = 2f,
            ),
        )

        assertEquals(0f, state.spectrum.bass, 0f)
        assertEquals(0f, state.spectrum.mid, 0f)
        assertEquals(0f, state.spectrum.treble, 0f)
        assertEquals(1f, state.spectrum.amplitude, 0f)
    }

    private fun resolve(
        preferences: PlayerVisualEffectsPreferences = PlayerVisualEffectsPreferences(),
        artwork: VisualPalette? = null,
        audio: SpectrumFrame? = SpectrumFrame.Zero,
        lifecycleStarted: Boolean = true,
        controlsVisible: Boolean = true,
        userInteracting: Boolean = false,
        powerSave: Boolean = false,
        mode: VisualPerformanceMode = preferences.performanceMode,
    ) = PlaybackVisualStateResolver.resolve(
        preferences = preferences.copy(performanceMode = mode),
        artworkPalette = artwork,
        audioSpectrum = audio,
        lifecycleStarted = lifecycleStarted,
        controlsVisible = controlsVisible,
        userInteracting = userInteracting,
        powerSave = powerSave,
        reduceMotion = false,
        rendererBackend = RendererBackend.NONE,
    )
}
