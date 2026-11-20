package com.mediahub.core.database.repository

/**
 * 本地播放进度的保留策略（#103 部分）：
 * "继续观看"只需要近期记录，更早的条目既不会再被展示，也会无限占用本地数据库。
 * 保留窗口默认 90 天；删除采用严格小于截止点，恰好落在截止点的条目保留。
 */
object ProgressRetentionPolicy {

    const val DEFAULT_RETENTION_DAYS: Int = 90

    private const val DAY_MS: Long = 24L * 60L * 60L * 1_000L

    /** 返回早于该时间戳的记录应被删除。 */
    fun cutoffEpochMs(nowEpochMs: Long, retentionDays: Int = DEFAULT_RETENTION_DAYS): Long {
        require(retentionDays > 0) { "retentionDays must be positive: $retentionDays" }
        return nowEpochMs - retentionDays.toLong() * DAY_MS
    }
}
