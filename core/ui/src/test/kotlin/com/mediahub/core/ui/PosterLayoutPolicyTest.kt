package com.mediahub.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterLayoutPolicyTest {

    @Test
    fun detailPosterWidthUsesWindowBuckets() {
        assertEquals(96.dp, PosterLayoutPolicy.detailPosterWidth(320))
        assertEquals(104.dp, PosterLayoutPolicy.detailPosterWidth(360))
        assertEquals(120.dp, PosterLayoutPolicy.detailPosterWidth(600))
        assertEquals(136.dp, PosterLayoutPolicy.detailPosterWidth(840))
    }

    @Test
    fun phoneUsesStandardPosterRatio() {
        assertEquals(2f / 3f, PosterLayoutPolicy.posterAspectRatio(320), 0.0001f)
        assertEquals(2f / 3f, PosterLayoutPolicy.posterAspectRatio(411), 0.0001f)
    }

    @Test
    fun largerWindowsWidenPosterRatio() {
        assertEquals(0.70f, PosterLayoutPolicy.posterAspectRatio(600), 0.0001f)
        assertEquals(0.72f, PosterLayoutPolicy.posterAspectRatio(840), 0.0001f)
    }
}
