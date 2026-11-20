package com.mediahub.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the detail-screen layout tokens introduced for audit D6/D7.
 */
class DetailLayoutTokensTest {

    @Test
    fun detailScreenGutterIsSixteenDpForEverySection() {
        assertEquals(16, DETAIL_SCREEN_GUTTER_DP)
        assertNotEquals(20, DETAIL_SCREEN_GUTTER_DP)
    }

    @Test
    fun primaryCtaIsFiftyTwoDpTall() {
        assertEquals(52, DETAIL_PRIMARY_CTA_HEIGHT_DP)
        assertNotEquals(54, DETAIL_PRIMARY_CTA_HEIGHT_DP)
    }

    @Test
    fun primaryCtaSpacingIsSixteenDp() {
        assertEquals(16, DETAIL_PRIMARY_CTA_PADDING_DP)
        assertNotEquals(20, DETAIL_PRIMARY_CTA_PADDING_DP)
    }
}