package com.mediahub.metadata.season

import com.mediahub.metadata.MetadataEpisode
import com.mediahub.metadata.MetadataSeason
import com.mediahub.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonCatalogTest {

    @Test
    fun `marks external seasons missing from library`() {
        val merged = SeasonCatalog.merge(
            external = listOf(
                season(0, "特别篇", 5, "2009-02-17"),
                season(1, "第 1 季", 7, "2008-01-20"),
                season(6, "第 6 季", 10, "2027-01-01"),
            ),
            library = listOf(
                Season(
                    serverId = "srv",
                    id = "season-1",
                    seriesId = "show-1",
                    name = "本地第一季",
                    seasonNumber = 1,
                    episodeCount = 7,
                ),
            ),
        )

        assertEquals(listOf(0, 1, 6), merged.map { it.seasonNumber })
        assertFalse(merged.first { it.seasonNumber == 0 }.inLibrary)
        assertFalse(merged.first { it.seasonNumber == 6 }.inLibrary)
        val first = merged.first { it.seasonNumber == 1 }
        assertTrue(first.inLibrary)
        assertEquals("season-1", first.librarySeasonId)
        assertEquals("第 1 季", first.title)
    }

    @Test
    fun `keeps local seasons unknown to external source`() {
        val merged = SeasonCatalog.merge(
            external = listOf(season(1, "第 1 季", 8, "2008-01-20")),
            library = listOf(
                Season("srv", "season-9", "show-1", "本地花絮", 9, episodeCount = 2),
            ),
        )

        assertEquals(listOf(1, 9), merged.map { it.seasonNumber })
        val local = merged.first { it.seasonNumber == 9 }
        assertTrue(local.inLibrary)
        assertEquals("本地花絮", local.title)
        assertEquals(2, local.episodeCount)
    }

    @Test
    fun `falls back to local artwork and overview when external is missing`() {
        val merged = SeasonCatalog.merge(
            external = emptyList(),
            library = listOf(
                Season(
                    serverId = "srv",
                    id = "season-1",
                    seriesId = "show-1",
                    name = "第 1 季",
                    seasonNumber = 1,
                    overview = "本地简介",
                    imageUrl = "https://img.local/s1.jpg",
                ),
            ),
        )

        val only = merged.single()
        assertEquals("本地简介", only.overview)
        assertEquals("https://img.local/s1.jpg", only.posterUrl)
        assertNull(only.airDate)
    }

    @Test
    fun `episode catalog overlays library availability`() {
        val merged = EpisodeCatalog.merge(
            seasonNumber = 1,
            external = listOf(
                episode(1, "试播集", "2008-01-20", 58),
                episode(2, "猫入袋中", "2008-01-27", 48),
            ),
            library = listOf(
                com.mediahub.model.Episode(
                    serverId = "srv",
                    id = "ep-1",
                    seriesId = "show-1",
                    seasonId = "season-1",
                    name = "本地试播集",
                    episodeNumber = 1,
                    runtimeMs = 3_480_000,
                ),
            ),
        )

        assertEquals(listOf(1, 2), merged.map { it.episodeNumber })
        assertTrue(merged.first { it.episodeNumber == 1 }.inLibrary)
        assertEquals("ep-1", merged.first { it.episodeNumber == 1 }.libraryEpisodeId)
        assertFalse(merged.first { it.episodeNumber == 2 }.inLibrary)
        assertEquals(58, merged.first { it.episodeNumber == 1 }.runtimeMinutes)
    }

    private fun season(number: Int, title: String, count: Int?, airDate: String?) =
        MetadataSeason(
            seasonNumber = number,
            title = title,
            episodeCount = count,
            airDate = airDate,
        )

    private fun episode(number: Int, title: String, airDate: String?, runtime: Int?) =
        MetadataEpisode(
            seasonNumber = 1,
            episodeNumber = number,
            title = title,
            airDate = airDate,
            runtimeMinutes = runtime,
        )
}
