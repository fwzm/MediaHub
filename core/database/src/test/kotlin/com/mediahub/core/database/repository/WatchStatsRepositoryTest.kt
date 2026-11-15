package com.mediahub.core.database.repository

import android.content.Context
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.model.MediaType
import com.mediahub.model.WatchSession
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 条目 141/139：统计聚合与隐藏内容过滤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchStatsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WatchStatsRepository
    private val today = LocalDate.of(2026, 11, 14)
    private val fixedNowMs = today.atStartOfDay().plusHours(12).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WatchStatsRepository(
            db = db,
            hiddenContent = RoomHiddenContentRepository(db),
            zoneId = ZoneOffset.UTC,
            clockMs = { fixedNowMs },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun epochMs(dayOffsetFromToday: Long): Long =
        today.plusDays(dayOffsetFromToday).atStartOfDay().plusHours(10)
            .toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(
        id: String,
        itemId: String,
        type: MediaType,
        title: String,
        watchedMs: Long,
        startOffsetDays: Long,
        serverId: String = "srv-1",
    ): WatchSession {
        val start = epochMs(startOffsetDays)
        return WatchSession(
            id = id,
            serverId = serverId,
            itemId = itemId,
            itemType = type.name,
            title = title,
            watchedMs = watchedMs,
            startedAtEpochMs = start,
            endedAtEpochMs = start + watchedMs,
            positionStartMs = 0,
            positionEndMs = watchedMs,
            durationMs = 6_000_000,
            completed = false,
        )
    }

    @Test
    fun `overview aggregates totals distinct counts calendar trend and review`() = runBlocking {
        repository.record(session("s1", "movie-1", MediaType.MOVIE, "电影 A", 60_000, -4))
        repository.record(session("s2", "movie-1", MediaType.MOVIE, "电影 A", 30_000, -3))
        repository.record(session("s3", "ep-1", MediaType.EPISODE, "剧集 E1", 45_000, -3))
        repository.record(session("s4", "ep-2", MediaType.EPISODE, "剧集 E2", 15_000, -2))

        val overview = repository.observeOverview(trendDays = 7).first()

        assertEquals(150_000L, overview.totalWatchedMs)
        assertEquals(1, overview.movieCount)
        assertEquals(2, overview.episodeCount)
        assertEquals(3, overview.watchDayCount)
        assertEquals(4, overview.sessionCount)
        assertEquals(3, overview.calendar.size)
        assertEquals(7, overview.trend.size)
        // 趋势按"今天"结束，缺失日期补 0。
        assertEquals(0L, overview.trend.last().watchedMs)
        assertEquals(15_000L, overview.trend[overview.trend.size - 3].watchedMs)
        // 历史按结束时间倒序。
        assertEquals("s4", overview.history.first().id)
        // 回顾：连续 3 天观看、时长最高的标题。
        assertEquals(3, overview.review.longestStreakDays)
        assertEquals("电影 A", overview.review.topTitle)
        assertEquals(90_000L, overview.review.topTitleWatchedMs)
    }

    @Test
    fun `hidden content is excluded from stats history and review`() = runBlocking {
        repository.record(session("s1", "movie-1", MediaType.MOVIE, "电影 A", 60_000, -2))
        repository.record(session("s2", "ep-1", MediaType.EPISODE, "剧集 E1", 30_000, -1))

        db.hiddenContentDao().upsert(
            com.mediahub.core.database.entity.HiddenContentEntity(
                serverId = "srv-1",
                itemId = "movie-1",
                hiddenAtEpochMs = fixedNowMs,
            ),
        )

        val overview = repository.observeOverview(trendDays = 7).first()
        val history = repository.observeHistory(limit = 10).first()

        assertEquals(30_000L, overview.totalWatchedMs)
        assertEquals(0, overview.movieCount)
        assertEquals(1, overview.episodeCount)
        assertEquals(listOf("s2"), history.map { it.id })
        assertEquals("剧集 E1", overview.review.topTitle)
    }

    @Test
    fun `empty database yields empty overview`() = runBlocking {
        val overview = repository.observeOverview(trendDays = 7).first()

        assertTrue(overview.isEmpty)
        assertTrue(overview.history.isEmpty())
    }

    @Test
    fun `history honors limit and ordering`() = runBlocking {
        repeat(5) { index ->
            repository.record(
                session(
                    id = "s$index",
                    itemId = "movie-$index",
                    type = MediaType.MOVIE,
                    title = "电影 $index",
                    watchedMs = 10_000,
                    startOffsetDays = -(index + 1).toLong(),
                ),
            )
        }

        val history = repository.observeHistory(limit = 3).first()

        assertEquals(3, history.size)
        assertEquals(listOf("s0", "s1", "s2"), history.map { it.id })
    }
}
