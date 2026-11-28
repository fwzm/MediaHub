package com.mediahub.feature.player

import com.mediahub.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #46：只对单片/单集/本地视频在无续播时自动退出。 */
class PlaybackCompletionPolicyTest {
    @Test
    fun `movie episode and local video auto exit when nothing continues`() {
        assertTrue(PlaybackCompletionPolicy.shouldAutoExit(MediaType.MOVIE))
        assertTrue(PlaybackCompletionPolicy.shouldAutoExit(MediaType.EPISODE))
        assertTrue(PlaybackCompletionPolicy.shouldAutoExit(MediaType.VIDEO))
    }

    @Test
    fun `audio live tv and containers never auto exit`() {
        listOf(
            MediaType.SERIES, MediaType.SEASON, MediaType.FOLDER,
            MediaType.AUDIO, MediaType.LIVE_TV, MediaType.OTHER,
        ).forEach { type ->
            assertFalse(type.name, PlaybackCompletionPolicy.shouldAutoExit(type))
        }
    }

    @Test
    fun `grace period stays short enough to feel automatic`() {
        assertTrue(PlaybackCompletionPolicy.AUTO_EXIT_GRACE_MS in 500L..3_000L)
    }
}
