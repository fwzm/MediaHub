package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 播完自动连播的纯逻辑验收：编号缺失（0 / null）永远排最后、跨季接续正确。
 *
 * 这两点是最容易写错的地方：排序若把 seasonNumber=0 的特别篇当第 0 季，
 * S01E01 播完就会「连播」到特别篇；跨季若只比集号，S01E03 会停住不动。
 */
class UpNextResolverTest {

    private fun season(id: String, number: Int, seriesId: String = "series-1") = Season(
        serverId = "srv-1", id = id, seriesId = seriesId, name = "Season $number", seasonNumber = number,
    )

    private fun episode(
        id: String,
        number: Int,
        seasonId: String,
        indexNumber: Int? = null,
    ) = Episode(
        serverId = "srv-1", id = id, seriesId = "series-1", seasonId = seasonId,
        name = "Episode $number", episodeNumber = number, indexNumber = indexNumber,
    )

    @Test
    fun `seasons are ordered by season number`() {
        val ordered = UpNextResolver.orderedSeasons(
            listOf(season("s2", 2), season("s1", 1), season("s3", 3)),
        )
        assertEquals(listOf("s1", "s2", "s3"), ordered.map { it.id })
    }

    @Test
    fun `a season without a usable number never sorts first`() {
        val ordered = UpNextResolver.orderedSeasons(
            listOf(season("specials", 0), season("s1", 1), season("s2", 2), season("weird", -1)),
        )
        assertEquals(listOf("s1", "s2", "specials", "weird"), ordered.map { it.id })
    }

    @Test
    fun `episodes are ordered by number and then by index number`() {
        val ordered = UpNextResolver.orderedEpisodes(
            listOf(
                episode("e2", 2, "s1"),
                episode("e1b", 1, "s1", indexNumber = 2),
                episode("e1a", 1, "s1", indexNumber = 1),
            ),
        )
        assertEquals(listOf("e1a", "e1b", "e2"), ordered.map { it.id })
    }

    @Test
    fun `an episode without a usable number always sorts last`() {
        val ordered = UpNextResolver.orderedEpisodes(
            listOf(episode("special", 0, "s1"), episode("e1", 1, "s1"), episode("e2", 2, "s1")),
        )
        assertEquals(listOf("e1", "e2", "special"), ordered.map { it.id })
    }

    @Test
    fun `next in season returns the following episode`() {
        val episodes = listOf(episode("e1", 1, "s1"), episode("e2", 2, "s1"), episode("e3", 3, "s1"))
        val next = UpNextResolver.nextInSeason(episodes, currentEpisodeId = "e1", currentEpisodeNumber = 1)
        assertEquals("e2", next?.id)
    }

    @Test
    fun `next in season is null on the last episode`() {
        val episodes = listOf(episode("e1", 1, "s1"), episode("e2", 2, "s1"))
        val next = UpNextResolver.nextInSeason(episodes, currentEpisodeId = "e2", currentEpisodeNumber = 2)
        assertNull(next)
    }

    @Test
    fun `next in season falls back to the number when the id is unknown`() {
        val episodes = listOf(episode("e1", 1, "s1"), episode("e2", 2, "s1"), episode("e4", 4, "s1"))
        val next = UpNextResolver.nextInSeason(episodes, currentEpisodeId = null, currentEpisodeNumber = 2)
        assertEquals("e4", next?.id)
    }

    @Test
    fun `next in season gives up when neither the id nor the number is usable`() {
        val episodes = listOf(episode("e1", 1, "s1"), episode("e2", 2, "s1"))
        assertNull(UpNextResolver.nextInSeason(episodes, currentEpisodeId = "missing", currentEpisodeNumber = null))
        assertNull(UpNextResolver.nextInSeason(episodes, currentEpisodeId = "missing", currentEpisodeNumber = 0))
    }

    @Test
    fun `following seasons lists everything after the current one`() {
        val seasons = listOf(season("s1", 1), season("s2", 2), season("s3", 3))
        val following = UpNextResolver.followingSeasons(seasons, currentSeasonId = "s1", currentSeasonNumber = 1)
        assertEquals(listOf("s2", "s3"), following.map { it.id })
    }

    @Test
    fun `following seasons is empty for the last season`() {
        val seasons = listOf(season("s1", 1), season("s2", 2))
        val following = UpNextResolver.followingSeasons(seasons, currentSeasonId = "s2", currentSeasonNumber = 2)
        assertTrue(following.isEmpty())
    }

    @Test
    fun `a season without a usable number follows the numbered seasons`() {
        val seasons = listOf(season("specials", 0), season("s1", 1), season("s2", 2))
        val following = UpNextResolver.followingSeasons(seasons, currentSeasonId = "s2", currentSeasonNumber = 2)
        assertEquals(listOf("specials"), following.map { it.id })
    }

    @Test
    fun `following seasons by number when the current season is not listed`() {
        val seasons = listOf(season("s1", 1), season("s2", 2), season("specials", 0))
        val following = UpNextResolver.followingSeasons(seasons, currentSeasonId = "gone", currentSeasonNumber = 1)
        assertEquals(listOf("s2"), following.map { it.id })
    }

    @Test
    fun `following seasons is empty when nothing identifies the current season`() {
        val seasons = listOf(season("s1", 1), season("s2", 2))
        assertTrue(UpNextResolver.followingSeasons(seasons, currentSeasonId = null, currentSeasonNumber = null).isEmpty())
        assertTrue(UpNextResolver.followingSeasons(emptyList(), currentSeasonId = "s1", currentSeasonNumber = 1).isEmpty())
    }

    @Test
    fun `resolve next stays inside the season`() {
        val seasons = listOf(season("s1", 1), season("s2", 2))
        val bySeason = mapOf(
            "s1" to listOf(episode("s1e1", 1, "s1"), episode("s1e2", 2, "s1")),
            "s2" to listOf(episode("s2e1", 1, "s2")),
        )
        val target = UpNextResolver.resolveNext(
            seasons = seasons,
            episodesBySeason = bySeason,
            currentSeasonId = "s1",
            currentSeasonNumber = 1,
            currentEpisodeId = "s1e1",
            currentEpisodeNumber = 1,
        )
        assertEquals("s1e2", target?.episode?.id)
        assertEquals(1, target?.seasonNumber)
        assertFalse(target!!.crossSeason)
    }

    @Test
    fun `resolve next crosses into the following season`() {
        val seasons = listOf(season("s1", 1), season("s2", 2))
        val bySeason = mapOf(
            "s1" to listOf(episode("s1e1", 1, "s1"), episode("s1e2", 2, "s1"), episode("s1e3", 3, "s1")),
            "s2" to listOf(episode("s2e1", 1, "s2"), episode("s2e2", 2, "s2")),
        )
        val target = UpNextResolver.resolveNext(
            seasons = seasons,
            episodesBySeason = bySeason,
            currentSeasonId = "s1",
            currentSeasonNumber = 1,
            currentEpisodeId = "s1e3",
            currentEpisodeNumber = 3,
        )
        assertEquals("s2e1", target?.episode?.id)
        assertEquals("s2", target?.seasonId)
        assertEquals(2, target?.seasonNumber)
        assertTrue(target!!.crossSeason)
    }

    @Test
    fun `resolve next skips seasons without episodes`() {
        val seasons = listOf(season("s1", 1), season("s2", 2), season("s3", 3))
        val bySeason = mapOf(
            "s1" to listOf(episode("s1e1", 1, "s1")),
            "s2" to emptyList<Episode>(),
            "s3" to listOf(episode("s3e1", 1, "s3")),
        )
        val target = UpNextResolver.resolveNext(
            seasons = seasons,
            episodesBySeason = bySeason,
            currentSeasonId = "s1",
            currentSeasonNumber = 1,
            currentEpisodeId = "s1e1",
            currentEpisodeNumber = 1,
        )
        assertEquals("s3e1", target?.episode?.id)
        assertTrue(target!!.crossSeason)
    }

    @Test
    fun `resolve next is null after the last episode of the last season`() {
        val seasons = listOf(season("s1", 1), season("s2", 2))
        val bySeason = mapOf(
            "s1" to listOf(episode("s1e1", 1, "s1")),
            "s2" to listOf(episode("s2e1", 1, "s2")),
        )
        val target = UpNextResolver.resolveNext(
            seasons = seasons,
            episodesBySeason = bySeason,
            currentSeasonId = "s2",
            currentSeasonNumber = 2,
            currentEpisodeId = "s2e1",
            currentEpisodeNumber = 1,
        )
        assertNull(target)
    }

    @Test
    fun `resolve next never jumps into a season without a usable number before the numbered ones`() {
        val seasons = listOf(season("specials", 0), season("s1", 1), season("s2", 2))
        val bySeason = mapOf(
            "specials" to listOf(episode("sp1", 1, "specials")),
            "s1" to listOf(episode("s1e1", 1, "s1")),
            "s2" to listOf(episode("s2e1", 1, "s2")),
        )
        val target = UpNextResolver.resolveNext(
            seasons = seasons,
            episodesBySeason = bySeason,
            currentSeasonId = "s1",
            currentSeasonNumber = 1,
            currentEpisodeId = "s1e1",
            currentEpisodeNumber = 1,
        )
        assertEquals("s2e1", target?.episode?.id)
    }

    @Test
    fun `first episode tolerates an empty season`() {
        assertNull(UpNextResolver.firstEpisode(emptyList()))
        assertEquals("e1", UpNextResolver.firstEpisode(listOf(episode("e2", 2, "s1"), episode("e1", 1, "s1")))?.id)
    }

    @Test
    fun `number helpers treat zero and negative values as unknown`() {
        assertFalse(UpNextResolver.isKnownNumber(null))
        assertFalse(UpNextResolver.isKnownNumber(0))
        assertFalse(UpNextResolver.isKnownNumber(-3))
        assertTrue(UpNextResolver.isKnownNumber(1))
    }
}
