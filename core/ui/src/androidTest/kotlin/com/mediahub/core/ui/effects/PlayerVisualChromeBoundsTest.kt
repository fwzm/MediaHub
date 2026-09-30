package com.mediahub.core.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.model.PlayerVisualPreset
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual chrome layout/drawing, not only the independent normalized mask helper. */
@RunWith(AndroidJUnit4::class)
class PlayerVisualChromeBoundsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tallLandscapeControlsKeepAmbientOutsideSubtitleSafeBand() {
        val mask = PlayerVisualMaskConfig()
        val backend = AtomicReference(RendererBackend.NONE)
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(width = ROOT_WIDTH.dp, height = ROOT_HEIGHT.dp)
                    .background(Color.White)
                    .testTag(ROOT_TAG),
            ) {
                PlayerVisualChromeBackground(
                    request = PlayerVisualRenderRequest(
                        preset = PlayerVisualPreset.AURORA,
                        intensity = 1f,
                        audioReactive = false,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .testTag(PlayerVisualTestTags.PLAYER_CONTROLS),
                    maxAmbientHeight = ROOT_HEIGHT.dp * (1f - mask.bottomStart),
                    forceFallback = true,
                    scrimTopAlpha = 0f,
                    scrimBottomAlpha = 0f,
                    onBackendChanged = backend::set,
                ) {
                    // Deliberately much taller than the allowed 18dp ambient band. Transparent
                    // content/scrim isolates any unexpected renderer pixels in the safe region.
                    Box(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT.dp))
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            backend.get() == RendererBackend.FALLBACK_GRADIENT
        }
        composeRule.waitForIdle()

        val rootNode = composeRule.onNodeWithTag(ROOT_TAG)
        val controlsNode = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_CONTROLS)
        val ambientNode = composeRule.onNodeWithTag(PlayerVisualTestTags.CHROME_AMBIENT)
        rootNode.assertWidthIsEqualTo(ROOT_WIDTH.dp).assertHeightIsEqualTo(ROOT_HEIGHT.dp)
        controlsNode.assertHeightIsEqualTo(CONTROLS_HEIGHT.dp)
        ambientNode.assertHeightIsEqualTo(AMBIENT_HEIGHT.dp)

        val root = rootNode.fetchSemanticsNode().boundsInRoot
        val controls = controlsNode.fetchSemanticsNode().boundsInRoot
        val ambient = ambientNode.fetchSemanticsNode().boundsInRoot
        assertEquals("controls stay bottom-aligned without expanding the player", root.bottom, controls.bottom, 1f)
        assertEquals("ambient stays at the player bottom", root.bottom, ambient.bottom, 1f)
        assertEquals("ambient spans the controls width", controls.width, ambient.width, 1f)
        assertTrue(
            "actual renderer bounds must not enter the subtitle-safe band (allow one rounding pixel)",
            ambient.top >= root.top + root.height * mask.bottomStart - 1f,
        )
        assertTrue("the renderer must not inherit the full 120dp controls height", ambient.height < controls.height)

        val pixels = rootNode.captureToImage().toPixelMap()
        val white = Color.White.toArgb()
        // Check every complete pixel row above the normalized boundary; the single fractional
        // boundary row is excluded because dp-to-pixel rounding differs across emulator densities.
        val safeRows = floor(pixels.height * mask.bottomStart).toInt()
        var firstTintedSafePixel: Pair<Int, Int>? = null
        for (y in 0 until safeRows) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() != white && firstTintedSafePixel == null) {
                    firstTintedSafePixel = x to y
                }
            }
        }
        assertEquals("upper 91% must retain the untouched white background", null, firstTintedSafePixel)
        assertTrue(
            "the bounded bottom ambient must still render visible pixels",
            (safeRows until pixels.height).any { y ->
                (0 until pixels.width).any { x -> pixels[x, y].toArgb() != white }
            },
        )
        assertEquals(RendererBackend.FALLBACK_GRADIENT, backend.get())
    }

    private companion object {
        const val ROOT_TAG = "player_visual_chrome_bounds_root"
        const val ROOT_WIDTH = 360
        const val ROOT_HEIGHT = 200
        const val CONTROLS_HEIGHT = 120
        const val AMBIENT_HEIGHT = 18
    }
}
