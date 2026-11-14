package com.mediahub.feature.stats

import com.mediahub.model.WatchDayStat
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目 141：观影日历/趋势所需的纯逻辑。
 */
class StatsCalendarTest {

    @Test
    fun `calendar starts on monday and pads leading blanks`() {
        // 2026-11-01 是周日 → 周一为周起点时前面应补 6 个空格。
        val calendar = buildStatsCalendarMonth(
            year = 2026,
            month = 11,
            watchByDay = mapOf(
                LocalDate.of(2026, 11, 3).toEpochDay() to WatchDayStat(
                    epochDay = LocalDate.of(2026, 11, 3).toEpochDay(),
                    watchedMs = 90_000,
                    sessionCount = 2,
                ),
            ),
        )

        assertEquals(0, calendar.cells.size % 7)
        assertEquals(6, calendar.cells.indexOfFirst { it != null })
        assertNull(calendar.cells[0])
        val third = calendar.cells.first { it?.dayOfMonth == 3 }!!
        assertEquals(90_000L, third.watchedMs)
        assertEquals(2, third.sessionCount)
        assertEquals(90_000L, calendar.totalWatchedMs)
    }

    @Test
    fun `calendar with month starting on monday has no leading blanks`() {
        // 2026-06-01 是周一。
        val calendar = buildStatsCalendarMonth(2026, 6, emptyMap())

        assertEquals(1, calendar.cells.first()?.dayOfMonth)
        assertEquals(30, calendar.cells.count { it != null })
    }

    @Test
    fun `duration formatting keeps minutes and hours readable`() {
        assertEquals("0 分钟", formatWatchDuration(0))
        assertEquals("1 分钟", formatWatchDuration(1))
        assertEquals("45 分钟", formatWatchDuration(45 * 60_000L))
        assertEquals("3 小时", formatWatchDuration(3 * 3_600_000L))
        assertEquals("3 小时 12 分钟", formatWatchDuration(3 * 3_600_000L + 12 * 60_000L))
    }

    @Test
    fun `watchByDay indexes calendar entries`() {
        val stats = listOf(
            WatchDayStat(10, 1_000, 1),
            WatchDayStat(11, 2_000, 2),
        )
        val indexed = watchByDayOf(stats)

        assertEquals(2, indexed.size)
        assertEquals(2_000L, indexed.getValue(11).watchedMs)
        assertTrue(indexed.containsKey(10))
    }
}
