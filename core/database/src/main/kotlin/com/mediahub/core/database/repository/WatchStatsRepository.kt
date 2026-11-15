package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.HiddenContentEntity
import com.mediahub.core.database.entity.WatchSessionEntity
import com.mediahub.model.MediaType
import com.mediahub.model.WatchDayStat
import com.mediahub.model.WatchReview
import com.mediahub.model.WatchSession
import com.mediahub.model.WatchStatsOverview
import com.mediahub.model.WatchTrendPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 隐藏内容键（serverId + itemId）。 */
data class HiddenKey(val serverId: String, val itemId: String)

/**
 * 隐藏空间标记的读接口（条目 139）。
 *
 * 统计侧只依赖该接口，不关心隐藏空间如何锁定/解锁：命中即过滤。
 */
interface HiddenContentStore {
    fun observeHiddenKeys(): Flow<Set<HiddenKey>>
    suspend fun markHidden(serverId: String, itemId: String)
    suspend fun unhide(serverId: String, itemId: String)
    suspend fun isHidden(serverId: String, itemId: String): Boolean
}

@Singleton
class RoomHiddenContentRepository @Inject constructor(
    db: AppDatabase,
) : HiddenContentStore {
    private val dao = db.hiddenContentDao()

    override fun observeHiddenKeys(): Flow<Set<HiddenKey>> =
        dao.observeAll().map { rows ->
            rows.mapTo(mutableSetOf()) { HiddenKey(it.serverId, it.itemId) }
        }

    override suspend fun markHidden(serverId: String, itemId: String) {
        dao.upsert(
            HiddenContentEntity(
                serverId = serverId,
                itemId = itemId,
                hiddenAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun unhide(serverId: String, itemId: String) = dao.delete(serverId, itemId)

    override suspend fun isHidden(serverId: String, itemId: String): Boolean = dao.contains(serverId, itemId)
}

/** 观影统计读写面（条目 141）。 */
interface WatchStatsStore {
    fun observeOverview(trendDays: Int = DEFAULT_TREND_DAYS): Flow<WatchStatsOverview>

    fun observeHistory(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<WatchSession>>

    suspend fun record(session: WatchSession)

    suspend fun clearAll()

    companion object {
        const val DEFAULT_TREND_DAYS = 30
        const val DEFAULT_HISTORY_LIMIT = 200
    }
}

/**
 * 观影统计仓库：会话表 → 聚合快照。
 *
 * 统计只读会话表并**无条件剔除**隐藏内容（HiddenContentStore），
 * 因此隐藏空间锁定与否都不会泄露相关记录。
 */
@Singleton
class WatchStatsRepository internal constructor(
    db: AppDatabase,
    private val hiddenContent: HiddenContentStore,
    private val zoneId: ZoneId,
    private val clockMs: () -> Long,
) : WatchStatsStore {
    private val dao = db.watchStatsDao()

    @Inject
    constructor(
        db: AppDatabase,
        hiddenContent: RoomHiddenContentRepository,
    ) : this(db, hiddenContent, ZoneId.systemDefault(), System::currentTimeMillis)

    override fun observeOverview(trendDays: Int): Flow<WatchStatsOverview> =
        combine(dao.observeAll(), hiddenContent.observeHiddenKeys()) { entities, hidden ->
            val visible = entities.asSequence()
                .map { it.toDomain() }
                .filterNot { HiddenKey(it.serverId, it.itemId) in hidden }
                .toList()
            aggregate(visible, trendDays)
        }.distinctUntilChanged()

    override fun observeHistory(limit: Int): Flow<List<WatchSession>> =
        combine(dao.observeAll(), hiddenContent.observeHiddenKeys()) { entities, hidden ->
            entities.asSequence()
                .map { it.toDomain() }
                .filterNot { HiddenKey(it.serverId, it.itemId) in hidden }
                .sortedByDescending { it.endedAtEpochMs }
                .take(limit)
                .toList()
        }.distinctUntilChanged()

    override suspend fun record(session: WatchSession) {
        dao.upsert(session.toEntity())
    }

    override suspend fun clearAll() = dao.deleteAll()

    private fun aggregate(sessions: List<WatchSession>, trendDays: Int): WatchStatsOverview {
        if (sessions.isEmpty()) return WatchStatsOverview.Empty
        val byDay = sessions.groupBy { epochDay(it.startedAtEpochMs) }
        val calendar = byDay.entries
            .map { (day, list) ->
                WatchDayStat(day, list.sumOf { it.watchedMs }, list.size)
            }
            .sortedBy { it.epochDay }

        val today = epochDay(clockMs())
        val trend = (trendDays - 1 downTo 0).map { offset ->
            val day = today - offset
            WatchTrendPoint(day, byDay[day]?.sumOf { it.watchedMs } ?: 0L)
        }

        val movieCount = sessions.asSequence()
            .filter { it.itemType == MediaType.MOVIE.name }
            .map { it.itemId }
            .distinct()
            .count()
        val episodeCount = sessions.asSequence()
            .filter { it.itemType == MediaType.EPISODE.name }
            .map { it.itemId }
            .distinct()
            .count()

        val top = sessions.groupBy { it.title.ifBlank { it.itemId } }
            .mapValues { (_, list) -> list.sumOf { it.watchedMs } }
            .maxByOrNull { it.value }

        return WatchStatsOverview(
            totalWatchedMs = sessions.sumOf { it.watchedMs },
            movieCount = movieCount,
            episodeCount = episodeCount,
            watchDayCount = byDay.size,
            sessionCount = sessions.size,
            calendar = calendar,
            trend = trend,
            history = sessions.sortedByDescending { it.endedAtEpochMs }
                .take(WatchStatsStore.DEFAULT_HISTORY_LIMIT),
            review = WatchReview(
                totalWatchedMs = sessions.sumOf { it.watchedMs },
                sessionCount = sessions.size,
                movieCount = movieCount,
                episodeCount = episodeCount,
                activeDayCount = byDay.size,
                longestStreakDays = longestStreak(calendar.map { it.epochDay }),
                topTitle = top?.key,
                topTitleWatchedMs = top?.value ?: 0L,
            ),
        )
    }

    private fun epochDay(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().toEpochDay()

    private fun longestStreak(sortedDays: List<Long>): Int {
        if (sortedDays.isEmpty()) return 0
        var best = 1
        var current = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i] - sortedDays[i - 1] == 1L) {
                current++
                if (current > best) best = current
            } else {
                current = 1
            }
        }
        return best
    }
}

internal fun WatchSessionEntity.toDomain(): WatchSession = WatchSession(
    id = id,
    serverId = serverId,
    itemId = itemId,
    itemType = itemType,
    title = title,
    watchedMs = watchedMs,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    positionStartMs = positionStartMs,
    positionEndMs = positionEndMs,
    durationMs = durationMs,
    completed = completed,
)

internal fun WatchSession.toEntity(): WatchSessionEntity = WatchSessionEntity(
    id = id,
    serverId = serverId,
    itemId = itemId,
    itemType = itemType,
    title = title,
    watchedMs = watchedMs,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    positionStartMs = positionStartMs,
    positionEndMs = positionEndMs,
    durationMs = durationMs,
    completed = completed,
)

/** 播放器 → 统计记录器的采样入口。 */
interface WatchStatsRecorder {
    /** 观看统计开关（条目 122）：关闭后不再产生新记录，已有记录不动。 */
    fun setEnabled(enabled: Boolean)

    fun onSample(
        serverId: String,
        itemId: String,
        itemTitle: String?,
        itemType: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        speed: Float,
    )

    /** 周期性落盘（同一会话 Upsert，不产生重复记录）。 */
    suspend fun checkpoint()

    /** 结束当前会话并落盘。 */
    suspend fun finish()

    /** 丢弃当前会话（不落盘）。 */
    fun reset()
}

/**
 * 会话累加器（条目 139 的"观看时长偏少"修复点）。
 *
 * 旧口径按"最后位置"或"时长上限"估算，回看/回拖与分段观看都会被少算。
 * 这里按采样之间的**正向内容增量**累计，并对超出合理范围的正向跳变
 * （拖动/自动连播跳转）按墙钟时间封顶，既不会把一次拖动算成看完，
 * 也不会漏掉二次观看。
 */
@Singleton
class DefaultWatchStatsRecorder internal constructor(
    private val store: WatchStatsStore,
    private val clockMs: () -> Long,
) : WatchStatsRecorder {
    @Inject
    constructor(store: WatchStatsRepository) : this(store, System::currentTimeMillis)

    private val lock = Any()
    private var enabled = true
    private var current: Accumulation? = null

    private class Accumulation(
        val key: String,
        val serverId: String,
        val itemId: String,
        var itemType: String,
        var title: String,
        val sessionId: String,
        val startedAtEpochMs: Long,
        var lastSampleAtEpochMs: Long,
        var lastPositionMs: Long,
        val positionStartMs: Long,
        var watchedMs: Long = 0,
        var maxDurationMs: Long = 0,
        var positionEndMs: Long = 0,
        var completed: Boolean = false,
        var persistedWatchedMs: Long = -1,
    )

    override fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            this.enabled = enabled
            if (!enabled) current = null
        }
    }

    override fun onSample(
        serverId: String,
        itemId: String,
        itemTitle: String?,
        itemType: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        speed: Float,
    ) {
        val now = clockMs()
        synchronized(lock) {
            if (!enabled) return
            val key = serverId + "\u0000" + itemId
            var acc = current
            if (acc == null || acc.key != key) {
                acc = Accumulation(
                    key = key,
                    serverId = serverId,
                    itemId = itemId,
                    itemType = itemType,
                    title = itemTitle.orEmpty(),
                    sessionId = key + "#" + now,
                    startedAtEpochMs = now,
                    lastSampleAtEpochMs = now,
                    lastPositionMs = positionMs.coerceAtLeast(0),
                    positionStartMs = positionMs.coerceAtLeast(0),
                    positionEndMs = positionMs.coerceAtLeast(0),
                    maxDurationMs = durationMs.coerceAtLeast(0),
                )
                current = acc
            }
            if (acc.title.isBlank() && !itemTitle.isNullOrBlank()) acc.title = itemTitle
            if (acc.itemType != itemType) acc.itemType = itemType

            val elapsed = (now - acc.lastSampleAtEpochMs).coerceAtLeast(0)
            val speedFactor = speed.coerceIn(0.1f, 8f)
            val plausibleMax = (elapsed * speedFactor * PLAUSIBLE_FACTOR).toLong() + PLAUSIBLE_SLACK_MS
            val delta = positionMs - acc.lastPositionMs
            if (isPlaying && elapsed > 0) {
                val counted = if (delta >= 0) {
                    // 正常前进：只计实际消费的内容时长；一次大跨度拖动按墙钟封顶。
                    delta.coerceAtMost(plausibleMax)
                } else {
                    // 回拖/回看：墙钟时间仍在消费内容，按墙钟计，避免回看被漏算。
                    elapsed.coerceAtMost(plausibleMax)
                }
                acc.watchedMs += counted
            }
            acc.lastSampleAtEpochMs = now
            acc.lastPositionMs = positionMs.coerceAtLeast(0)
            acc.positionEndMs = positionMs.coerceAtLeast(0)
            if (durationMs > acc.maxDurationMs) acc.maxDurationMs = durationMs
            if (isComplete(positionMs, acc.maxDurationMs)) acc.completed = true
        }
    }

    override suspend fun checkpoint() {
        val snapshot = synchronized(lock) {
            val acc = current ?: return
            if (!enabled) return
            if (acc.watchedMs < MIN_SESSION_MS) return
            if (acc.watchedMs == acc.persistedWatchedMs) return
            acc.persistedWatchedMs = acc.watchedMs
            acc.toSession(clockMs())
        } ?: return
        store.record(snapshot)
    }

    override suspend fun finish() {
        val snapshot = synchronized(lock) {
            val acc = current ?: return
            current = null
            if (!enabled || acc.watchedMs < MIN_SESSION_MS) return
            if (acc.watchedMs == acc.persistedWatchedMs) return
            acc.toSession(clockMs())
        } ?: return
        store.record(snapshot)
    }

    override fun reset() {
        synchronized(lock) { current = null }
    }

    private fun Accumulation.toSession(endedAt: Long): WatchSession = WatchSession(
        id = sessionId,
        serverId = serverId,
        itemId = itemId,
        itemType = itemType,
        title = title,
        watchedMs = watchedMs,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAt.coerceAtLeast(startedAtEpochMs),
        positionStartMs = positionStartMs,
        positionEndMs = positionEndMs,
        durationMs = maxDurationMs,
        completed = completed,
    )

    private fun isComplete(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * COMPLETE_RATIO).toLong()

    private companion object {
        /** 小于 1 秒的会话视为误触，不落库。 */
        const val MIN_SESSION_MS = 1_000L

        /** 正向增量的合理上限系数（容忍 1.5× 倍速与采样抖动）。 */
        const val PLAUSIBLE_FACTOR = 1.5f
        const val PLAUSIBLE_SLACK_MS = 1_500L
        const val COMPLETE_RATIO = 0.95
    }
}

/** 供 UI 判断"今天"（日历默认定位）使用。 */
fun epochDayOf(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().toEpochDay()
