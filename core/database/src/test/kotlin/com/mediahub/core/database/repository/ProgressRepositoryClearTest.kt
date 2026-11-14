package com.mediahub.core.database.repository

import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 条目 63：清空播放记录。
 *
 * 契约：
 * - 一次清空所有 server 的记录，返回真实删除条数；
 * - 空表再次清空返回 0 且不抛错（幂等）；
 * - 清空后没有残留：续播位置与详情页回显都读不到旧记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressRepositoryClearTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProgressRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun progress(itemId: String, serverId: String = "srv-1") = PlaybackProgress(
        serverId = serverId,
        itemId = itemId,
        positionMs = 90_000,
        durationMs = 600_000,
        isPaused = true,
        updatedAtEpochMs = 1_000L,
        itemTitle = "电影 $itemId",
    )

    @Test
    fun `clearAll removes every server record and returns the deleted count`() = runBlocking {
        repository.save(progress("m1"))
        repository.save(progress("m2"))
        repository.save(progress("m3", serverId = "srv-2"))

        assertEquals(3, repository.clearAll())

        assertEquals(emptyList<PlaybackProgress>(), repository.observeContinueWatching(30).first())
        assertNull(repository.get("srv-1", "m1"))
        assertNull(repository.getResume("srv-2", "m3"))
    }

    @Test
    fun `clearAll is idempotent on an empty table`() = runBlocking {
        assertEquals(0, repository.clearAll())

        repository.save(progress("m1"))
        assertEquals(1, repository.clearAll())
        assertEquals("第二次清空不得抛错，也不得返回负数", 0, repository.clearAll())
    }
}
