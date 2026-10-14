package com.mediahub.core.ui.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real composable theme and Material components, not a replacement color resolver. */
@RunWith(AndroidJUnit4::class)
class PlayerVisualThemeCompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun paletteTransitionUpdatesComposedThemeSurfaceAndSliderWithoutLeakingOutsidePlayer() {
        val first = PlayerVisualPalette.from(
            VisualPalette(
                background = 0xFF080F20.toInt(),
                primary = 0xFFB5D8FF.toInt(),
                secondary = 0xFF344E83.toInt(),
                accent = 0xFF72C0FA.toInt(),
            ),
        )
        val second = PlayerVisualPalette.from(
            VisualPalette(
                background = 0xFFF8F0E5.toInt(),
                primary = 0xFFF7CF91.toInt(),
                secondary = 0xFF805331.toInt(),
                accent = 0xFFE48033.toInt(),
            ),
        )
        val activePalette = mutableStateOf(first)
        val composed = AtomicReference<ThemeSnapshot>()
        val outsidePrimary = AtomicReference<Color>()
        val outsideSurface = AtomicReference<Color>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta, surface = Color.White)) {
                val outside = MaterialTheme.colorScheme
                SideEffect {
                    outsidePrimary.set(outside.primary)
                    outsideSurface.set(outside.surface)
                }
                PlayerVisualTheme(palette = activePalette.value, transitionMillis = TRANSITION_MS) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        composed.set(
                            ThemeSnapshot(
                                primary = colors.primary,
                                onPrimary = colors.onPrimary,
                                secondary = colors.secondary,
                                tertiary = colors.tertiary,
                                surface = colors.surface,
                                surfaceVariant = colors.surfaceVariant,
                                onSurface = colors.onSurface,
                                onSurfaceVariant = colors.onSurfaceVariant,
                            ),
                        )
                    }
                    Column {
                        Surface(
                            modifier = Modifier.size(240.dp, 96.dp).testTag(SURFACE_TAG),
                            color = colors.surface,
                            contentColor = colors.onSurface,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // No explicit text color: Surface must supply the theme's content color.
                                Text("Playback", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Surface(color = colors.surface) {
                            Slider(
                                value = 0.5f,
                                onValueChange = {},
                                modifier = Modifier.size(280.dp, 64.dp).testTag(SLIDER_TAG),
                                // Same local Material mapping as the production player's seek bar.
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.primary,
                                    activeTrackColor = colors.primary,
                                    activeTickColor = colors.onPrimary,
                                    inactiveTrackColor = colors.surfaceVariant,
                                    inactiveTickColor = colors.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        val initial = checkNotNull(composed.get())
        assertMatchesPalette(initial, first)
        assertReadable(initial)
        assertSurfacePixels(initial)
        val beforeSlider = composeRule.onNodeWithTag(SLIDER_TAG).captureToImage().toPixelMap()
        assertSliderPixels(beforeSlider, initial)

        composeRule.runOnUiThread { activePalette.value = second }
        // Allow recomposition to retarget animateColorAsState before advancing its animation.
        composeRule.mainClock.advanceTimeByFrame()
        repeat(4) {
            composeRule.mainClock.advanceTimeBy(64)
            composeRule.waitForIdle()
            assertReadable(checkNotNull(composed.get()))
        }
        val during = checkNotNull(composed.get())
        assertTrue("the actual theme must animate, not snap", colorDistance(during.surface, first.surface) > 0.01f)
        assertTrue("the intermediate surface must not already be the endpoint", colorDistance(during.surface, second.surface) > 0.01f)

        composeRule.mainClock.advanceTimeBy(TRANSITION_MS.toLong() + 64)
        composeRule.waitForIdle()
        val final = checkNotNull(composed.get())
        assertMatchesPalette(final, second)
        assertReadable(final)
        assertSurfacePixels(final)
        val afterSlider = composeRule.onNodeWithTag(SLIDER_TAG).captureToImage().toPixelMap()
        assertSliderPixels(afterSlider, final)
        assertTrue("slider pixels must consume the new accent", countColor(afterSlider, final.primary) > 20)
        assertTrue("the old slider accent must no longer be painted", countColor(afterSlider, initial.primary) < 5)
        assertTrue("surface palette must actually change", colorDistance(initial.surface, final.surface) > 0.1f)
        assertTrue("slider palette must actually change", colorDistance(initial.primary, final.primary) > 0.1f)
        assertEquals("player theme remains scoped", Color.Magenta, outsidePrimary.get())
        assertEquals("host surface remains unchanged", Color.White, outsideSurface.get())
    }

    private fun assertMatchesPalette(actual: ThemeSnapshot, expected: PlayerVisualPalette) {
        assertColor("primary maps to the player accent", expected.accent, actual.primary)
        assertColor("onPrimary maps to onAccent", expected.onAccent, actual.onPrimary)
        assertColor("secondary follows the palette", expected.secondary, actual.secondary)
        assertColor("tertiary maps to the source primary", expected.primary, actual.tertiary)
        assertColor("surface follows the palette", expected.surface, actual.surface)
        assertColor("surfaceVariant follows the palette", expected.surfaceVariant, actual.surfaceVariant)
        assertColor("onSurface follows the palette", expected.onSurface, actual.onSurface)
        assertColor("onSurfaceVariant follows the palette", expected.onSurfaceVariant, actual.onSurfaceVariant)
    }

    private fun assertReadable(colors: ThemeSnapshot) {
        assertTrue("surface text retains WCAG AA contrast", contrastRatio(colors.onSurface, colors.surface) >= 4.5f)
        assertTrue("variant text retains WCAG AA contrast", contrastRatio(colors.onSurfaceVariant, colors.surfaceVariant) >= 4.5f)
        assertTrue("accent text retains WCAG AA contrast", contrastRatio(colors.onPrimary, colors.primary) >= 4.5f)
    }

    private fun assertSurfacePixels(colors: ThemeSnapshot) {
        val pixels = composeRule.onNodeWithTag(SURFACE_TAG).captureToImage().toPixelMap()
        assertTrue("real Surface paints the composed background", countColor(pixels, colors.surface) > pixels.width * pixels.height / 2)
        assertTrue("real Text inherits Surface content color", countColor(pixels, colors.onSurface) > 8)
    }

    private fun assertSliderPixels(pixels: PixelMap, colors: ThemeSnapshot) {
        // Count interior colors instead of asserting exact geometry, density, or antialiased edges.
        assertTrue("real Slider paints its active track/thumb", countColor(pixels, colors.primary) > 20)
        assertTrue("real Slider paints its local inactive track", countColor(pixels, colors.surfaceVariant) > 20)
    }

    private fun countColor(pixels: PixelMap, expected: Color): Int {
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (colorDistance(pixels[x, y], expected) <= PIXEL_TOLERANCE) count++
            }
        }
        return count
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertTrue(message, colorDistance(expected, actual) <= PIXEL_TOLERANCE)
    }

    private fun colorDistance(first: Color, second: Color): Float = maxOf(
        abs(first.red - second.red),
        abs(first.green - second.green),
        abs(first.blue - second.blue),
        abs(first.alpha - second.alpha),
    )

    private data class ThemeSnapshot(
        val primary: Color,
        val onPrimary: Color,
        val secondary: Color,
        val tertiary: Color,
        val surface: Color,
        val surfaceVariant: Color,
        val onSurface: Color,
        val onSurfaceVariant: Color,
    )

    private companion object {
        const val TRANSITION_MS = 500
        const val SURFACE_TAG = "player_theme_surface"
        const val SLIDER_TAG = "player_theme_slider"
        const val PIXEL_TOLERANCE = 3f / 255f
    }
}
