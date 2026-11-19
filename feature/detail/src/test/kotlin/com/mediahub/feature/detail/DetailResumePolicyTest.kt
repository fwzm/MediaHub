package com.mediahub.feature.detail

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailResumePolicyTest {

    private fun item(
        id: String,
        type: MediaType = MediaType.EPISODE,
        playbackPositionMs: Long = 0L,
        playedPercentage: Double? = null,
        playCount: Int = 0,
    ): MediaItem = MediaItem(
        serverId = "srv",
        id = id,
        type = type,
        title = id,
        userData = UserData(
            playedPercentage = playedPercentage,
            playCount = playCount,
            playbackPositionMs = playbackPositionMs,
        ),
    )

    @Test
    fun inProgressEpisodeIsDetected() {
        assertTrue(DetailResumePolicy.isInProgress(item("e1", playbackPositionMs = 60_000L)))
        assertTrue(DetailResumePolicy.isInProgress(item("e2", playedPercentage = 42.0)))
        assertFalse(DetailResumePolicy.isInProgress(item("e3", playedPercentage = 95.0)))
        assertFalse(DetailResumePolicy.isInProgress(item("e4")))
        assertFalse(DetailResumePolicy.isInProgress(item("movie", type = MediaType.MOVIE, playbackPositionMs = 60_000L)))
    }

    @Test
    fun currentEpisodePrefersFirstInProgressEntry() {
        val episodes = listOf(
            item("e1", playedPercentage = 100.0),
            item("e2", playbackPositionMs = 12_000L),
            item("e3", playbackPositionMs = 30_000L),
        )
        assertEquals("e2", DetailResumePolicy.currentEpisodeId(episodes))
        assertNull(DetailResumePolicy.currentEpisodeId(listOf(item("e1", playedPercentage = 100.0))))
    }

    @Test
    fun findInProgressSeasonReturnsOwningSeason() {
        val seasons = listOf(
            item("s1", type = MediaType.SEASON),
            item("s2", type = MediaType.SEASON),
        )
        val episodes = mapOf(
            "s1" to listOf(item("e1", playedPercentage = 100.0)),
            "s2" to listOf(item("e2", playbackPositionMs = 90_000L)),
        )
        assertEquals("s2", DetailResumePolicy.findInProgressSeason(seasons, episodes)?.id)
        assertNull(DetailResumePolicy.findInProgressSeason(seasons, emptyMap()))
    }
}
