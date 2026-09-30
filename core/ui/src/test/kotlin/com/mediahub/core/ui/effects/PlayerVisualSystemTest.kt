package com.mediahub.core.ui.effects

import androidx.compose.ui.graphics.Color
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.VisualPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayerVisualSystemTest {

    @Test
    fun `backend selector keeps the feature available through a static fallback`() {
        assertEquals(
            RendererBackend.NONE,
            RendererBackendSelector.select(apiLevel = 36, enabled = false),
        )
        assertEquals(
            RendererBackend.FALLBACK_GRADIENT,
            RendererBackendSelector.select(apiLevel = 32, enabled = true),
        )
        assertEquals(
            RendererBackend.RUNTIME_SHADER,
            RendererBackendSelector.select(apiLevel = 33, enabled = true),
        )
        assertEquals(
            RendererBackend.FALLBACK_GRADIENT,
            RendererBackendSelector.select(
                apiLevel = 36,
                enabled = true,
                runtimeShaderUsable = false,
            ),
        )
        assertEquals(
            RendererBackend.FALLBACK_GRADIENT,
            RendererBackendSelector.select(
                apiLevel = 36,
                enabled = true,
                runtimeShaderUsable = true,
                forceFallback = true,
            ),
        )
        assertEquals(
            RendererBackend.NONE,
            RendererBackendSelector.select(
                apiLevel = 36,
                enabled = false,
                forceFallback = true,
            ),
        )
    }

    @Test
    fun `shared runtime policy latches fallback and gates its clock`() {
        var forceFallback = false

        forceFallback = PlayerVisualRuntimePolicy.latchForceFallback(
            currentForceFallback = forceFallback,
            reportedBackend = RendererBackend.RUNTIME_SHADER,
        )
        assertFalse(forceFallback)
        assertTrue(
            PlayerVisualRuntimePolicy.shouldRunSharedClock(
                requestedRunning = true,
                forceFallback = forceFallback,
            ),
        )

        forceFallback = PlayerVisualRuntimePolicy.latchForceFallback(
            currentForceFallback = forceFallback,
            reportedBackend = RendererBackend.FALLBACK_GRADIENT,
        )
        assertTrue(forceFallback)
        assertFalse(
            PlayerVisualRuntimePolicy.shouldRunSharedClock(
                requestedRunning = true,
                forceFallback = forceFallback,
            ),
        )

        forceFallback = PlayerVisualRuntimePolicy.latchForceFallback(
            currentForceFallback = forceFallback,
            reportedBackend = RendererBackend.RUNTIME_SHADER,
        )
        assertTrue("fallback remains latched until a new runtime session resets it", forceFallback)
        assertFalse(
            PlayerVisualRuntimePolicy.shouldRunSharedClock(
                requestedRunning = false,
                forceFallback = false,
            ),
        )
    }

    @Test
    fun `hard gates resolve to a real zero-fps stop`() {
        val active = VisualFrameInputs(
            enabled = true,
            lifecycleStarted = true,
            controlsVisible = true,
        )

        listOf(
            active.copy(enabled = false),
            active.copy(lifecycleStarted = false),
            active.copy(controlsVisible = false),
        ).forEach { inputs ->
            assertEquals(VisualFrameDecision.Stopped, VisualFramePolicy.resolve(inputs))
        }
    }

    @Test
    fun `auto policy chooses 15 30 and 60 from activity`() {
        val base = VisualFrameInputs(
            enabled = true,
            lifecycleStarted = true,
            controlsVisible = true,
            performanceMode = VisualPerformanceMode.AUTO,
        )

        assertEquals(15, VisualFramePolicy.resolve(base.copy(intensity = 0.1f)).targetFps)
        assertEquals(30, VisualFramePolicy.resolve(base.copy(intensity = 0.35f)).targetFps)
        assertEquals(
            60,
            VisualFramePolicy.resolve(base.copy(intensity = 0.35f, userInteracting = true)).targetFps,
        )
    }

    @Test
    fun `auto policy hard stops at zero and sub-epsilon normalized intensity`() {
        val base = VisualFrameInputs(
            enabled = true,
            lifecycleStarted = true,
            controlsVisible = true,
            performanceMode = VisualPerformanceMode.AUTO,
        )

        assertEquals(VisualFrameDecision.Stopped, VisualFramePolicy.resolve(base.copy(intensity = 0f)))
        assertEquals(
            VisualFrameDecision.Stopped,
            VisualFramePolicy.resolve(base.copy(intensity = 0.0005f)),
        )
        assertEquals(
            VisualFrameDecision.Stopped,
            VisualFramePolicy.resolve(base.copy(intensity = Float.NaN)),
        )
    }

    @Test
    fun `high performance cannot override the zero-intensity hard stop`() {
        val decision = VisualFramePolicy.resolve(
            VisualFrameInputs(
                enabled = true,
                lifecycleStarted = true,
                controlsVisible = true,
                userInteracting = true,
                performanceMode = VisualPerformanceMode.HIGH,
                intensity = 0f,
            ),
        )

        assertEquals(VisualFrameDecision.Stopped, decision)
    }

    @Test
    fun `settings preview uses the same zero-intensity stop policy`() {
        assertEquals(
            VisualFrameDecision.Stopped,
            VisualFramePolicy.resolvePreview(
                enabled = true,
                lifecycleStarted = true,
                performanceMode = VisualPerformanceMode.HIGH,
                intensity = 0f,
            ),
        )
        assertEquals(
            30,
            VisualFramePolicy.resolvePreview(
                enabled = true,
                lifecycleStarted = true,
                performanceMode = VisualPerformanceMode.BALANCED,
                intensity = 0.5f,
            ).targetFps,
        )
    }

    @Test
    fun `settings preview respects system motion and battery restrictions even in high mode`() {
        val battery = VisualFramePolicy.resolvePreview(
            enabled = true,
            lifecycleStarted = true,
            performanceMode = VisualPerformanceMode.HIGH,
            intensity = 1f,
            powerSave = true,
        )
        assertEquals(15, battery.targetFps)
        assertEquals(0.65f, battery.motionScale, 0f)

        val reduced = VisualFramePolicy.resolvePreview(
            enabled = true,
            lifecycleStarted = true,
            performanceMode = VisualPerformanceMode.HIGH,
            intensity = 1f,
            reduceMotion = true,
        )
        assertEquals(15, reduced.targetFps)
        assertEquals(0.2f, reduced.motionScale, 0f)
        assertEquals(
            VisualFrameDecision.Stopped,
            VisualFramePolicy.resolvePreview(
                enabled = true,
                lifecycleStarted = false,
                performanceMode = VisualPerformanceMode.HIGH,
                intensity = 1f,
                powerSave = true,
                reduceMotion = true,
            ),
        )
    }

    @Test
    fun `performance intent remains bounded by power and reduce motion`() {
        val base = VisualFrameInputs(
            enabled = true,
            lifecycleStarted = true,
            controlsVisible = true,
            intensity = 1f,
        )

        assertEquals(
            15,
            VisualFramePolicy.resolve(base.copy(performanceMode = VisualPerformanceMode.BATTERY)).targetFps,
        )
        assertEquals(
            30,
            VisualFramePolicy.resolve(base.copy(performanceMode = VisualPerformanceMode.BALANCED)).targetFps,
        )
        assertEquals(
            60,
            VisualFramePolicy.resolve(base.copy(performanceMode = VisualPerformanceMode.HIGH)).targetFps,
        )

        val powerSave = VisualFramePolicy.resolve(
            base.copy(performanceMode = VisualPerformanceMode.HIGH, powerSave = true),
        )
        assertEquals(15, powerSave.targetFps)
        assertEquals(0.65f, powerSave.motionScale, 0f)

        val reduceMotion = VisualFramePolicy.resolve(
            base.copy(performanceMode = VisualPerformanceMode.HIGH, reduceMotion = true),
        )
        assertEquals(15, reduceMotion.targetFps)
        assertEquals(0.2f, reduceMotion.motionScale, 0f)
    }

    @Test
    fun `preset mapping exposes distinct production configurations`() {
        val aurora = style(PlayerVisualPreset.AURORA)
        val liquid = style(PlayerVisualPreset.LIQUID)
        val spectrum = style(PlayerVisualPreset.SPECTRUM)
        assertTrue(liquid.config.warp > aurora.config.warp)
        assertTrue(spectrum.audioReactive)
        assertFalse(aurora.audioReactive)
        assertFalse(liquid.audioReactive)
        assertNotEquals(aurora.config, liquid.config)
        assertNotEquals(liquid.config, spectrum.config)
        assertEquals(30, aurora.config.fps)
    }

    @Test
    fun `preset mapping clamps untrusted intensity and honors a palette override`() {
        val custom = VisualPalette(
            background = 0xFF010203.toInt(),
            primary = 0xFF112233.toInt(),
            secondary = 0xFF445566.toInt(),
            accent = 0xFF778899.toInt(),
        )
        val tooHigh = PlayerVisualPresetMapper.resolve(
            preset = PlayerVisualPreset.AURORA,
            intensity = Float.POSITIVE_INFINITY,
            targetFps = 30,
            paletteOverride = custom,
        )
        val nan = PlayerVisualPresetMapper.resolve(
            preset = PlayerVisualPreset.AURORA,
            intensity = Float.NaN,
            targetFps = 30,
        )

        assertEquals(custom, tooHigh.palette)
        assertEquals(0f, tooHigh.config.opacity, 0f)
        assertEquals(0f, nan.config.opacity, 0f)
    }

    @Test
    fun `mask is structurally transparent over video center and subtitle-safe area`() {
        assertEquals(0f, PlayerVisualMask.alphaAt(0.5f, 0.5f, controlsVisible = true), 0f)
        assertEquals(0f, PlayerVisualMask.alphaAt(0.5f, 0.8f, controlsVisible = true), 0f)
        assertEquals(0f, PlayerVisualMask.alphaAt(0.12f, 0.91f, controlsVisible = true), 0f)
        assertTrue(PlayerVisualMask.alphaAt(0f, 0.4f, controlsVisible = true) > 0.99f)
        assertTrue(PlayerVisualMask.alphaAt(1f, 0.4f, controlsVisible = true) > 0.99f)
        assertTrue(PlayerVisualMask.alphaAt(0.5f, 1f, controlsVisible = true) > 0.99f)
    }

    @Test
    fun `mask disappears with controls and rejects coordinates outside its normalized domain`() {
        assertEquals(0f, PlayerVisualMask.alphaAt(0f, 0.5f, controlsVisible = false), 0f)
        assertEquals(0f, PlayerVisualMask.alphaAt(-0.1f, 0.5f, controlsVisible = true), 0f)
        assertEquals(0f, PlayerVisualMask.alphaAt(0.5f, Float.NaN, controlsVisible = true), 0f)
    }

    @Test
    fun `player palette guarantees text and control contrast for dark and light artwork`() {
        val sources = listOf(
            VisualPalette.Fallback,
            VisualPalette(
                background = 0xFFF7F4EE.toInt(),
                primary = 0xFFF4EDDC.toInt(),
                secondary = 0xFFCFE0AC.toInt(),
                accent = 0xFFE8A47E.toInt(),
            ),
            VisualPalette(
                background = 0xFF121212.toInt(),
                primary = 0xFF1A1A1A.toInt(),
                secondary = 0xFF242424.toInt(),
                accent = 0xFF303030.toInt(),
            ),
        )

        sources.map(PlayerVisualPalette::from).forEach { palette ->
            assertTrue(contrastRatio(palette.onSurface, palette.surface) >= 4.5f)
            assertTrue(contrastRatio(palette.onSurfaceVariant, palette.surfaceVariant) >= 4.5f)
            assertTrue(contrastRatio(palette.accent, palette.surface) >= 3f)
            assertTrue(contrastRatio(palette.onAccent, palette.accent) >= 4.5f)
            assertEquals(1f, palette.surface.alpha, 0f)
        }
    }

    @Test
    fun `visual config rejects nonfinite and out of range parameters`() {
        assertThrows(IllegalArgumentException::class.java) { VisualEffectConfig(speed = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VisualEffectConfig(warp = 4.1f) }
        assertThrows(IllegalArgumentException::class.java) { VisualEffectConfig(opacity = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { VisualEffectConfig(audioGain = 4.1f) }
        assertThrows(IllegalArgumentException::class.java) { VisualEffectConfig(fps = 0) }
    }

    private fun style(preset: PlayerVisualPreset): PlayerVisualRenderStyle =
        PlayerVisualPresetMapper.resolve(
            preset = preset,
            intensity = 0.5f,
            targetFps = 30,
        )
}
