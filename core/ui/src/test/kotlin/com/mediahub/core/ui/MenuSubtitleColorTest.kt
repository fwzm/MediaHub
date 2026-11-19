package com.mediahub.core.ui

import androidx.compose.ui.graphics.Color
import com.mediahub.core.ui.effects.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSubtitleColorTest {

    @Test
    fun lowContrastSubtitleIsLiftedToWcagThreshold() {
        val background = Color.Black
        val candidate = Color(0xFF3A3A3A)
        val resolved = MenuSubtitleColor.resolve(candidate, background)
        assertNotEquals(candidate, resolved)
        assertTrue(contrastRatio(resolved, background) >= MenuSubtitleColor.MIN_CONTRAST)
    }

    @Test
    fun accessibleSubtitleIsPreserved() {
        val background = Color(0xFF121212)
        val candidate = Color.White
        assertEquals(candidate, MenuSubtitleColor.resolve(candidate, background))
    }

    @Test
    fun darkMenuSurfaceAlwaysProducesReadableSubtitle() {
        val background = Color(0xFF1B1B1B)
        val resolved = MenuSubtitleColor.resolve(Color(0xFF555555), background)
        assertTrue(contrastRatio(resolved, background) >= MenuSubtitleColor.MIN_CONTRAST)
    }
}
