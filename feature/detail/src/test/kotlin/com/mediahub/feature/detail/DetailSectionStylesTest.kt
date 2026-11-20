package com.mediahub.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉审计 D11：「季」用 FilterChip + 8dp 间距，但剧集行与简介又回归另一套排版。
 * 这里锁定详情页统一区块样式：区块标题走同一个 spec（演员 / 季 / 剧情简介），
 * 区块间距 16dp、季 chip 行间距 8dp。
 */
class DetailSectionStylesTest {

    @Test
    fun sectionsAreSeparatedBySixteenDp() {
        assertEquals(16, DETAIL_SECTION_SPACING_DP)
        assertNotEquals(20, DETAIL_SECTION_SPACING_DP)
    }

    @Test
    fun seasonChipRowUsesEightDpSpacing() {
        assertEquals(8, DETAIL_SEASON_CHIP_SPACING_DP)
        assertNotEquals(12, DETAIL_SEASON_CHIP_SPACING_DP)
    }

    @Test
    fun everyDetailSectionUsesTheSameHeaderSpec() {
        val actors = detailSectionSpec("演员")
        val seasons = detailSectionSpec("季")
        val synopsis = detailSectionSpec("剧情简介")

        assertEquals("演员", actors.title)
        assertEquals("季", seasons.title)
        assertEquals("剧情简介", synopsis.title)
        assertEquals(actors.topSpacingDp, seasons.topSpacingDp)
        assertEquals(seasons.topSpacingDp, synopsis.topSpacingDp)
        assertTrue(actors.topSpacingDp > 0)
    }
}
