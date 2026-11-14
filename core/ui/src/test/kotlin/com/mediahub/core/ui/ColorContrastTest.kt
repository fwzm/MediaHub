package com.mediahub.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 对比度（条目 103）：菜单副标题在黑色背景下的辨识度。
 *
 * 只做纯数值校验：公式一旦写错，或者有人把副标题颜色调暗，
 * 这里会立刻变红。
 */
class ColorContrastTest {

    @Test
    fun `luminance anchors are exact`() {
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF.toInt()), 0.0001)
        assertEquals(0.0, relativeLuminance(0xFF000000.toInt()), 0.0001)
    }

    @Test
    fun `black on white reaches the maximum ratio`() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `contrast ratio is direction independent`() {
        val fg = MenuSubtitleSpec.COLOR_ARGB
        val bg = MenuSubtitleSpec.BACKGROUND_ARGB
        assertEquals(contrastRatio(fg, bg), contrastRatio(bg, fg), 0.0001)
    }

    @Test
    fun `menu subtitle clears the AAA threshold on the app background`() {
        val ratio = contrastRatio(MenuSubtitleSpec.COLOR_ARGB, MenuSubtitleSpec.BACKGROUND_ARGB)
        assertTrue("ratio=$ratio", ratio >= MenuSubtitleSpec.MIN_CONTRAST)
    }

    @Test
    fun `menu subtitle also clears the threshold on raised surfaces`() {
        val ratio = contrastRatio(MenuSubtitleSpec.COLOR_ARGB, MenuSubtitleSpec.SURFACE_ARGB)
        assertTrue("ratio=$ratio", ratio >= MenuSubtitleSpec.MIN_CONTRAST)
    }

    @Test
    fun `new subtitle color is brighter than the legacy one`() {
        val legacy = contrastRatio(MenuSubtitleSpec.LEGACY_COLOR_ARGB, MenuSubtitleSpec.BACKGROUND_ARGB)
        val current = contrastRatio(MenuSubtitleSpec.COLOR_ARGB, MenuSubtitleSpec.BACKGROUND_ARGB)
        assertTrue("legacy=$legacy current=$current", current > legacy)
        assertTrue(MenuSubtitleSpec.FONT_SIZE_SP >= 13)
        assertTrue(MenuSubtitleSpec.FONT_WEIGHT >= 500)
    }
}
