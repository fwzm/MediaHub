package com.mediahub.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutPolicyTest {

    @Test
    fun libraryColumnsStayCompactOnPhones() {
        assertEquals(2, AdaptiveLayoutPolicy.libraryColumnCount(320))
        assertEquals(3, AdaptiveLayoutPolicy.libraryColumnCount(360))
        assertEquals(3, AdaptiveLayoutPolicy.libraryColumnCount(411))
    }

    @Test
    fun libraryColumnsExpandOnTablets() {
        assertEquals(4, AdaptiveLayoutPolicy.libraryColumnCount(600))
        assertEquals(5, AdaptiveLayoutPolicy.libraryColumnCount(840))
        assertEquals(6, AdaptiveLayoutPolicy.libraryColumnCount(1000))
        assertEquals(6, AdaptiveLayoutPolicy.libraryColumnCount(1280))
    }

    @Test
    fun continueWatchingCardWidthTracksWindowBuckets() {
        assertEquals(232.dp, AdaptiveLayoutPolicy.continueWatchingCardWidth(320))
        assertEquals(276.dp, AdaptiveLayoutPolicy.continueWatchingCardWidth(360))
        assertEquals(276.dp, AdaptiveLayoutPolicy.continueWatchingCardWidth(411))
        assertEquals(320.dp, AdaptiveLayoutPolicy.continueWatchingCardWidth(600))
        assertEquals(360.dp, AdaptiveLayoutPolicy.continueWatchingCardWidth(840))
    }
}
