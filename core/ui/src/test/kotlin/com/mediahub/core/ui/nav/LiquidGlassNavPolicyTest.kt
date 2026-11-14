package com.mediahub.core.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃导航栏策略（条目 29 / 70）。
 *
 * 这些常量与比例函数决定「玻璃层厚度」与「菜单高亮」的位置，
 * 全部是纯值计算，可在 JVM 上锁定，不依赖 Compose 运行时。
 */
class LiquidGlassNavPolicyTest {

    @Test
    fun `selected fraction spans first to last item`() {
        assertEquals(0f, navSelectedFraction(index = 0, selected = 0, count = 3), 0f)
        assertEquals(0.5f, navSelectedFraction(index = 1, selected = 1, count = 3), 0f)
        assertEquals(1f, navSelectedFraction(index = 2, selected = 2, count = 3), 0f)
    }

    @Test
    fun `selected fraction is safe for single and empty bars`() {
        assertEquals(0f, navSelectedFraction(index = 0, selected = 0, count = 1), 0f)
        assertEquals(0f, navSelectedFraction(index = 3, selected = 1, count = 0), 0f)
    }

    @Test
    fun `out of range item index is clamped`() {
        assertEquals(1f, navSelectedFraction(index = 99, selected = 0, count = 3), 0f)
        assertEquals(0f, navSelectedFraction(index = -4, selected = 0, count = 3), 0f)
    }

    @Test
    fun `emphasis marks exactly one highlighted menu entry`() {
        val emphasis = (0 until 3).map { navItemEmphasis(index = it, selected = 1) }
        assertEquals(1f, emphasis[1], 0f)
        assertTrue(emphasis[0] in 0f..1f)
        assertTrue(emphasis[2] in 0f..1f)
        assertTrue(emphasis[0] < emphasis[1])
    }

    @Test
    fun `glass alphas stay translucent`() {
        assertTrue(NAV_BAR_GLASS_ALPHA > 0f && NAV_BAR_GLASS_ALPHA < 1f)
        assertTrue(NAV_BAR_SHEEN_ALPHA > 0f && NAV_BAR_SHEEN_ALPHA < NAV_BAR_GLASS_ALPHA)
    }

    @Test
    fun `selected index falls back to the first entry`() {
        val items = listOf(
            NavBarItemSpec(id = "home", label = "首页"),
            NavBarItemSpec(id = "search", label = "搜索"),
        )
        assertEquals(1, navSelectedIndex(items, "search"))
        assertEquals(0, navSelectedIndex(items, "missing"))
        assertEquals(0, navSelectedIndex(emptyList(), "home"))
    }
}
