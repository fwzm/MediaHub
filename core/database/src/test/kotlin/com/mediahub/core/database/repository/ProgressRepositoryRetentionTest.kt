package com.mediahub.core.database.repository

import android.content.Context
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.PlaybackProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 播放进度保留清理（#103 部分）：只删早于截止点的记录，边界记录与非陈旧记录必须保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressRepositoryRetentionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ProgressRepository

    private val dayMs = 24L * 60L * 60L * 1_000L
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProgressRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun progress(itemId: String, updatedAtEpochMs: Long) = PlaybackProgressEntity(
        serverId = "srv-1",
        itemId = itemId,
        positionMs = 1_000L,
        durationMs = 10_000L,
        isPaused = true,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    @Test
    fun `apply retention deletes only records older than the cutoff`() = runBlocking {
        db.playbackProgressDao().upsertAll(
            listOf(
                progress("old", now - 91L * dayMs),
                progress("boundary", now - 90L * dayMs),
                progress("fresh", now - dayMs),
            ),
        )

        val deleted = repository.applyRetention(nowEpochMs = now, retentionDays = 90)

        assertEquals(1, deleted)
        assertEquals(listOf("boundary", "fresh"), db.playbackProgressDao().getAll().map { it.itemId }.sorted())
    }

    @Test
    fun `custom retention window removes records outside the window`() = runBlocking {
        db.playbackProgressDao().upsertAll(
            listOf(
                progress("boundary90", now - 90L * dayMs),
                progress("fresh", now - dayMs),
            ),
        )

        val deleted = repository.applyRetention(nowEpochMs = now, retentionDays = 30)

        assertEquals(1, deleted)
        assertEquals(listOf("fresh"), db.playbackProgressDao().getAll().map { it.itemId })
    }
}
