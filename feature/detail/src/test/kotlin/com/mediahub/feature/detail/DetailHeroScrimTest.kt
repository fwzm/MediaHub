package com.mediahub.feature.detail

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉审计 D4：详情页 hero 的标题列没有独立 scrim，白字压在亮 backdrop 上对比度不足。
 * 收敛方案 = 标题列自带一层局部竖直渐变 scrim，顶部最重（保证白字对比度），向下淡出。
 */
class DetailHeroScrimTest {

    private val background = Color(0xFF101317)

    @Test
    fun titleScrimStartsOpaqueEnoughForWhiteTitle() {
        val stops = detailTitleScrimStops(background)
        val topAlpha = stops.first().second.alpha
        assertTrue("top scrim alpha $topAlpha must be >= 0.80", topAlpha >= 0.80f)
    }

    @Test
    fun titleScrimFadesTowardsTheBottom() {
        val stops = detailTitleScrimStops(background)
        assertTrue("scrim needs at least two stops", stops.size >= 2)
        assertEquals(0f, stops.first().first, 0.0001f)
        assertEquals(1f, stops.last().first, 0.0001f)
        for (i in 1 until stops.size) {
            assertTrue(
                "offsets must ascend: ${stops[i - 1].first} -> ${stops[i].first}",
                stops[i].first > stops[i - 1].first,
            )
            assertTrue(
                "alpha must fade: ${stops[i - 1].second.alpha} -> ${stops[i].second.alpha}",
                stops[i].second.alpha < stops[i - 1].second.alpha,
            )
        }
    }

    @Test
    fun titleScrimTintsWithThemeBackground() {
        val stops = detailTitleScrimStops(background)
        stops.forEach { (_, color) ->
            assertEquals("scrim must tint with the theme background", background.red, color.red, 0.0001f)
            assertEquals(background.green, color.green, 0.0001f)
            assertEquals(background.blue, color.blue, 0.0001f)
        }
        assertTrue("top stop must not be fully transparent", stops.first().second.alpha > 0f)
    }
}
