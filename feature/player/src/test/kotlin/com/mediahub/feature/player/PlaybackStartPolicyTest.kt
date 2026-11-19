package com.mediahub.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStartPolicyTest {

    @Test
    fun `explicit line switch position wins over stored resume`() {
        assertEquals(42_000L, PlaybackStartPolicy.startPositionMs(explicitStartMs = 42_000L, resumeMs = 3_000L))
    }

    @Test
    fun `no explicit position falls back to stored resume`() {
        assertEquals(3_000L, PlaybackStartPolicy.startPositionMs(explicitStartMs = null, resumeMs = 3_000L))
    }

    @Test
    fun `explicit zero is respected`() {
        assertEquals(0L, PlaybackStartPolicy.startPositionMs(explicitStartMs = 0L, resumeMs = 9_000L))
    }

    @Test
    fun `negative values are ignored`() {
        assertEquals(0L, PlaybackStartPolicy.startPositionMs(explicitStartMs = -5L, resumeMs = -1L))
    }
}

