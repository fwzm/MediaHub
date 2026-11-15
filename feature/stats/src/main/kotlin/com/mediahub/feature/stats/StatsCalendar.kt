package com.mediahub.feature.stats

import com.mediahub.model.WatchDayStat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 观影日历的一格。 */
data class StatsCalendarCell(
    val epochDay: Long,
    val dayOfMonth: Int,
    val watchedMs: Long,
    val sessionCount: Int,
)

/**
 * 一个月的观影日历。
 *
 * [cells] 前补空格（周一为一周起点），长度补齐 7 的倍数，UI 直接按 7 列渲染。
 */
data class StatsCalendarMonth(
    val year: Int,
    val month: Int,
    val cells: List<StatsCalendarCell?>,
    val totalWatchedMs: Long,
) {
    val weekCount: Int get() = cells.size / 7
}

fun buildStatsCalendarMonth(
    year: Int,
    month: Int,
    watchByDay: Map<Long, WatchDayStat>,
): StatsCalendarMonth {
    val first = LocalDate.of(year, month, 1)
    val dayCount = first.lengthOfMonth()
    // 周一 = 1 … 周日 = 7；日历从周一起排。
    val leadingBlanks = first.dayOfWeek.value - 1
    val cells = ArrayList<StatsCalendarCell?>(leadingBlanks + dayCount + 6)
    repeat(leadingBlanks) { cells += null }
    var total = 0L
    for (day in 1..dayCount) {
        val date = first.withDayOfMonth(day)
        val stat = watchByDay[date.toEpochDay()]
        total += stat?.watchedMs ?: 0L
        cells += StatsCalendarCell(
            epochDay = date.toEpochDay(),
            dayOfMonth = day,
            watchedMs = stat?.watchedMs ?: 0L,
            sessionCount = stat?.sessionCount ?: 0,
        )
    }
    while (cells.size % 7 != 0) cells += null
    return StatsCalendarMonth(year, month, cells, total)
}

/** 把会话日历聚合成 epochDay → 当日统计，供日历/趋势复用。 */
fun watchByDayOf(calendar: List<WatchDayStat>): Map<Long, WatchDayStat> =
    calendar.associateBy { it.epochDay }

/** 观看时长文案：不足 1 分钟按分钟展示，超过 1 小时带小时。 */
fun formatWatchDuration(watchedMs: Long): String {
    val totalMinutes = (watchedMs / 60_000L).coerceAtLeast(if (watchedMs > 0) 1 else 0)
    return when {
        totalMinutes <= 0 -> "0 分钟"
        totalMinutes < 60L -> "$totalMinutes 分钟"
        else -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
        }
    }
}

/** 历史行日期文案（本地时区）。 */
fun formatWatchDate(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val date = java.time.Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate()
    return String.format(Locale.ROOT, "%02d-%02d", date.monthValue, date.dayOfMonth)
}
