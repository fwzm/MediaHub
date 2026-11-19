package com.mediahub.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test fun `network rate wins over a cached range`() {
        assertEquals(
            PlaybackSpeedStatus.NETWORK,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 120_000,
                isBuffering = true,
                positionMs = 10_000,
                bufferedPositionMs = 60_000,
                playWhenReady = true,
                isEnded = false,
            ),
        )
    }

    @Test fun `cached range is reported while playback intent is active`() {
        assertEquals(
            PlaybackSpeedStatus.CACHED,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 0,
                isBuffering = true,
                positionMs = 10_000,
                bufferedPositionMs = 11_000,
                playWhenReady = true,
                isEnded = false,
            ),
        )
    }

    @Test fun `cache below threshold is not cached`() {
        assertEquals(
            PlaybackSpeedStatus.UNKNOWN,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 0,
                isBuffering = false,
                positionMs = 10_000,
                bufferedPositionMs = 10_999,
                playWhenReady = true,
                isEnded = false,
            ),
        )
    }

    @Test fun `paused playback does not claim a cached range`() {
        assertEquals(
            PlaybackSpeedStatus.UNKNOWN,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 0,
                isBuffering = false,
                positionMs = 10_000,
                bufferedPositionMs = 60_000,
                playWhenReady = false,
                isEnded = false,
            ),
        )
    }

    @Test fun `ended playback does not claim a cached range`() {
        assertEquals(
            PlaybackSpeedStatus.UNKNOWN,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 0,
                isBuffering = false,
                positionMs = 10_000,
                bufferedPositionMs = 60_000,
                playWhenReady = true,
                isEnded = true,
            ),
        )
    }

    @Test fun `buffering without cache reports buffering`() {
        assertEquals(
            PlaybackSpeedStatus.BUFFERING,
            PlaybackSpeedPolicy.status(
                bytesPerSecond = 0,
                isBuffering = true,
                positionMs = 10_000,
                bufferedPositionMs = 10_000,
                playWhenReady = true,
                isEnded = false,
            ),
        )
    }

    @Test fun `label kind distinguishes rate cached buffering and unknown`() {
        assertEquals(PlaybackSpeedLabel.RATE, PlaybackSpeedPolicy.labelKind(1, PlaybackSpeedStatus.NETWORK))
        assertEquals(PlaybackSpeedLabel.CACHED, PlaybackSpeedPolicy.labelKind(0, PlaybackSpeedStatus.CACHED))
        assertEquals(PlaybackSpeedLabel.BUFFERING, PlaybackSpeedPolicy.labelKind(0, PlaybackSpeedStatus.BUFFERING))
        assertEquals(PlaybackSpeedLabel.UNKNOWN, PlaybackSpeedPolicy.labelKind(0, PlaybackSpeedStatus.UNKNOWN))
    }

    @Test fun `buffering indicator is hidden for cached playback`() {
        assertFalse(PlaybackSpeedPolicy.shouldShowBufferingIndicator(true, PlaybackSpeedStatus.CACHED))
        assertTrue(PlaybackSpeedPolicy.shouldShowBufferingIndicator(true, PlaybackSpeedStatus.BUFFERING))
        assertTrue(PlaybackSpeedPolicy.shouldShowBufferingIndicator(true, PlaybackSpeedStatus.UNKNOWN))
        assertFalse(PlaybackSpeedPolicy.shouldShowBufferingIndicator(false, PlaybackSpeedStatus.UNKNOWN))
    }
}
