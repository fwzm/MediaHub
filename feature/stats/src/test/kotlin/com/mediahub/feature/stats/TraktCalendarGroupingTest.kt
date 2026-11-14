package com.mediahub.feature.stats

import com.mediahub.model.TraktCalendarEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 条目 146：追更日历分组（按天、天内按季/集排序）。
 */
class TraktCalendarGroupingTest {

    @Test
    fun `groups by date sorted ascending and episodes by season number`() {
        val entries = listOf(
            entry(date = "2026-11-16", season = 1, episode = 2),
            entry(date = "2026-11-15", season = 2, episode = 5),
            entry(date = "2026-11-15", season = 1, episode = 9),
            entry(date = "2026-11-15", season = 1, episode = 3),
        )

        val days = groupTraktCalendar(entries)

        assertEquals(listOf("2026-11-15", "2026-11-16"), days.map { it.date })
        assertEquals(
            listOf(1 to 3, 1 to 9, 2 to 5),
            days.first().entries.map { it.season to it.episode },
        )
    }

    private fun entry(date: String, season: Int, episode: Int) = TraktCalendarEntry(
        traktId = 1,
        showTitle = "测试剧集",
        season = season,
        episode = episode,
        episodeTitle = "第 $episode 集",
        firstAired = date,
    )
}
