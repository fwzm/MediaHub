package com.mediahub.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiStateProgressTest {
    @Test fun `withProgress copies position duration buffered position and seekability`() {
        val before = PlaybackUiState(
            isPlaying = true,
            positionMs = 1_000,
            durationMs = 90_000,
            bufferedPositionMs = 5_000,
            isSeekable = false,
        )
        val after = before.withProgress(
            positionMs = 12_000,
            durationMs = 90_000,
            bufferedPositionMs = 45_000,
            isSeekable = true,
        )
        assertEquals(12_000L, after.positionMs)
        assertEquals(45_000L, after.bufferedPositionMs)
        assertTrue(after.isSeekable)
        assertTrue(after.isPlaying)
    }

    @Test fun `buffered position defaults to zero`() {
        assertEquals(0L, PlaybackUiState().bufferedPositionMs)
    }
}
