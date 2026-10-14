package com.mediahub.core.ui.effects

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowGlowRuntimeShaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pre33FallbackProducesPixelsWithoutRuntimeShader() {
        assumeTrue(Build.VERSION.SDK_INT < RendererBackendSelector.RUNTIME_SHADER_MIN_API)
        assertStaticFallback(forceFallback = false)
    }

    @Test
    fun forcedFallbackProducesPixelsWithoutSamplingAudio() {
        assertStaticFallback(forceFallback = true)
    }

    private fun assertStaticFallback(forceFallback: Boolean) {
        val backend = AtomicReference(RendererBackend.NONE)
        val audioSamples = AtomicInteger()
        composeRule.setContent {
            FlowGlowSurface(
                palette = FlowGlowPresets.AuroraDark.palette,
                config = FlowGlowPresets.AuroraDark.config.copy(opacity = 0.75f),
                running = true,
                forceFallback = forceFallback,
                spectrum = SpectrumProvider {
                    audioSamples.incrementAndGet()
                    SpectrumFrame.Zero
                },
                modifier = Modifier.size(SIZE.dp).testTag(TEST_TAG),
                onBackendChanged = backend::set,
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            backend.get() == RendererBackend.FALLBACK_GRADIENT
        }
        // A requested-running fallback must still become idle: it has no animation clock.
        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag(TEST_TAG).captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("fallback must remain visible", center.alpha > 0f)
        assertTrue(
            "fallback must retain the palette gradient",
            pixels[pixels.width / 4, pixels.height / 4] !=
                pixels[3 * pixels.width / 4, 3 * pixels.height / 4],
        )
        assertEquals(RendererBackend.FALLBACK_GRADIENT, backend.get())
        assertEquals("static fallback does not poll spectrum", 0, audioSamples.get())
    }

    @Test
    fun runtimeShaderCompilesAcceptsEveryUniformAndProducesPixels() {
        assumeTrue(Build.VERSION.SDK_INT >= RendererBackendSelector.RUNTIME_SHADER_MIN_API)
        // Compile the production source and explicitly exercise every uniform setter first. This
        // catches source/uniform drift before the real renderer is mounted.
        val shader = RuntimeShader(FlowGlowShader.SOURCE)
        FlowGlowShader.apply(
            shader = shader,
            width = SIZE.toFloat(),
            height = SIZE.toFloat(),
            timeSec = 1.25f,
            progress = 0.42f,
            palette = FlowGlowPresets.AuroraDark.palette,
            config = FlowGlowPresets.AuroraDark.config.copy(opacity = 0.75f),
            spectrum = SpectrumFrame(bass = 0.8f, mid = 0.45f, treble = 0.65f, amplitude = 0.7f),
        )

        // RuntimeShader is deliberately unsupported by a software Bitmap Canvas. Render through
        // the production Compose node so this assertion exercises an actual hardware-accelerated
        // Android draw pass, including the vendor shader compiler/driver path.
        val reportedBackend = AtomicReference(RendererBackend.NONE)
        composeRule.setContent {
            FlowGlowSurface(
                palette = FlowGlowPresets.AuroraDark.palette,
                config = FlowGlowPresets.AuroraDark.config.copy(opacity = 0.75f),
                spectrum = SpectrumProvider.alreadySmoothed {
                    SpectrumFrame(bass = 0.8f, mid = 0.45f, treble = 0.65f, amplitude = 0.7f)
                },
                progressProvider = { 0.42f },
                running = false,
                modifier = Modifier
                    .size(SIZE.dp)
                    .testTag(TEST_TAG),
                onBackendChanged = reportedBackend::set,
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            reportedBackend.get() != RendererBackend.NONE
        }
        composeRule.waitForIdle()
        assertEquals(RendererBackend.RUNTIME_SHADER, reportedBackend.get())

        val pixels = composeRule.onNodeWithTag(TEST_TAG).captureToImage().toPixelMap()
        val sampledColors = buildSet {
            for (y in 0 until pixels.height step SAMPLE_STRIDE) {
                for (x in 0 until pixels.width step SAMPLE_STRIDE) add(pixels[x, y])
            }
        }

        assertTrue("shader output must contain visible alpha", sampledColors.any { it.alpha > 0f })
        assertTrue("shader output must contain a non-uniform field", sampledColors.size > 1)
        assertEquals(RendererBackend.RUNTIME_SHADER, reportedBackend.get())
    }

    private companion object {
        const val SIZE = 96
        const val SAMPLE_STRIDE = 4
        const val TEST_TAG = "flowglow_runtime_shader"
    }
}
