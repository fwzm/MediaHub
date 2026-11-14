package com.mediahub.core.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃真模糊策略（收尾项 4）：API 31+ 走 RenderEffect 真模糊，
 * 低版本保留当前仿制方案，参数由这里统一决定。
 */
class GlassBlurPolicyTest {

    @Test
    fun `real blur requires api 31`() {
        assertFalse(supportsRealBackdropBlur(30))
        assertFalse(supportsRealBackdropBlur(26))
        assertTrue(supportsRealBackdropBlur(31))
        assertTrue(supportsRealBackdropBlur(34))
    }

    @Test
    fun `blur radius is zero on legacy and positive on 31 plus`() {
        assertEquals(0f, glassBlurRadiusDp(30), 0f)
        assertEquals(GLASS_BLUR_RADIUS_DP, glassBlurRadiusDp(31), 0f)
        assertTrue(GLASS_BLUR_RADIUS_DP > 0f)
    }

    @Test
    fun `backdrop becomes more opaque once it is actually blurred`() {
        assertEquals(NAV_BAR_GLASS_ALPHA, glassScrimAlpha(30), 0f)
        assertEquals(GLASS_SCRIM_ALPHA_BLURRED, glassScrimAlpha(33), 0f)
        assertTrue(glassScrimAlpha(33) < NAV_BAR_GLASS_ALPHA)
        assertTrue(glassScrimAlpha(33) > 0f)
    }
}
