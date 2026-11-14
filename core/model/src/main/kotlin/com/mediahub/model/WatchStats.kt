package com.mediahub.model

/**
 * 本地观影统计（条目 141）。
 *
 * 存储口径：每次播放会话落一条 [WatchSession]，统计值全部由会话聚合得出，
 * 不额外维护易失真的累计表。观看时长按"实际消费的内容时长"累计
 * （见 core:database 的会话累加器），因此回看、拖动后的二次观看都会被计入，
 * 修复"观看时长偏少"的旧口径问题。
 */
data class WatchSession(
    val id: String,
    val serverId: String,
    val itemId: String,
    /** [MediaType].name；未知类型使用 OTHER。 */
    val itemType: String,
    val title: String,
    val watchedMs: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val positionStartMs: Long,
    val positionEndMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)

/** 观影日历中的一天（epochDay = LocalDate.toEpochDay）。 */
data class WatchDayStat(
    val epochDay: Long,
    val watchedMs: Long,
    val sessionCount: Int,
)

/** 趋势图的一天（缺失日期补 0，保证连续）。 */
data class WatchTrendPoint(
    val epochDay: Long,
    val watchedMs: Long,
)

/** 观影回顾（条目 141 的"观影回顾"）。 */
data class WatchReview(
    val totalWatchedMs: Long = 0,
    val sessionCount: Int = 0,
    val movieCount: Int = 0,
    val episodeCount: Int = 0,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val topTitle: String? = null,
    val topTitleWatchedMs: Long = 0,
)

/** 统计页一次加载所需的完整快照。 */
data class WatchStatsOverview(
    val totalWatchedMs: Long = 0,
    val movieCount: Int = 0,
    val episodeCount: Int = 0,
    val watchDayCount: Int = 0,
    val sessionCount: Int = 0,
    val calendar: List<WatchDayStat> = emptyList(),
    val trend: List<WatchTrendPoint> = emptyList(),
    val history: List<WatchSession> = emptyList(),
    val review: WatchReview = WatchReview(),
) {
    val isEmpty: Boolean get() = sessionCount == 0

    companion object {
        val Empty = WatchStatsOverview()
    }
}
