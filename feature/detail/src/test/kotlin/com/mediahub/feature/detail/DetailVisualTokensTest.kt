package com.mediahub.feature.detail

import androidx.compose.foundation.shape.CircleShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailVisualTokensTest {

    @Test
    fun directorRowUsesEightDpVerticalPadding() {
        assertEquals(8, DETAIL_DIRECTOR_ROW_VERTICAL_PADDING_DP)
        assertNotEquals(2, DETAIL_DIRECTOR_ROW_VERTICAL_PADDING_DP)
    }

    @Test
    fun actorAvatarIsAFullCircle() {
        assertSame(CircleShape, detailAvatarShape())
        assertTrue(detailAvatarShape() is androidx.compose.foundation.shape.RoundedCornerShape)
    }

    @Test
    fun synopsisIsCollapsedToFourLinesAndExpandsUnbounded() {
        assertEquals(4, detailSynopsisMaxLines(expanded = false))
        assertEquals(Int.MAX_VALUE, detailSynopsisMaxLines(expanded = true))
        assertTrue(detailSynopsisMaxLines(expanded = true) > 4)
        assertFalse(detailSynopsisMaxLines(expanded = false) > 4)
    }
}
