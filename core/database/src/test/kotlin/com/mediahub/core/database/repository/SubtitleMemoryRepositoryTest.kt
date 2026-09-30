package com.mediahub.core.database.repository

import android.content.Context
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 字幕匹配记忆（P2 字幕中心切片一）：
 * 版本键隔离（同片不同版本不串）/ 写读回放 / 手动选择后写覆盖 / 遗忘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleMemoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SubtitleMemoryRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SubtitleMemoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(
        itemId: String = "m1",
        sizeBytes: Long? = null,
        path: String? = "/media/movie.mkv",
        serverId: String = "srv-1",
    ): MediaItem = MediaItem(
        serverId = serverId,
        id = itemId,
        type = MediaType.MOVIE,
        title = "电影",
        sizeBytes = sizeBytes,
        path = path,
    )

    // ---- 键设计：serverId+itemId+sizeBytes 优先；无 size 回退路径指纹 ----

    @Test
    fun `version key prefers sizeBytes and falls back to path fingerprint`() {
        val withSize = SubtitleMemoryKeys.forItem(item(sizeBytes = 700_000_000L, path = "/a.mkv"))
        val withSizeAgain = SubtitleMemoryKeys.forItem(item(sizeBytes = 700_000_000L, path = "/other-place.mkv"))
        assertEquals(withSize, withSizeAgain)
        assertTrue(withSize.endsWith("|s:700000000"))

        val byPath1 = SubtitleMemoryKeys.forItem(item(sizeBytes = null, path = "/1080p.mkv"))
        val byPath2 = SubtitleMemoryKeys.forItem(item(sizeBytes = null, path = "/4k.mkv"))
        assertTrue(byPath1.contains("|p:"))
        assertEquals(byPath1, SubtitleMemoryKeys.forItem(item(sizeBytes = null, path = "/1080p.mkv")))
        // 路径不同 → 键不同（同片双版本隔离）
        assertTrue(byPath1 != byPath2)
    }

    // ---- 记忆键隔离：同片不同版本互不串 ----

    @Test
    fun `same movie different versions do not share memory`() = runBlocking {
        val key1080 = SubtitleMemoryKeys.forItem(item(sizeBytes = 1L, path = null))
        val key4k = SubtitleMemoryKeys.forItem(item(sizeBytes = 2L, path = null))

        repository.remember(
            SubtitleMemoryEntry(key1080, "srv-1", "http://nas/movie.zh.srt", offsetMs = 1500, updatedAtEpochMs = 0),
        )
        repository.remember(
            SubtitleMemoryEntry(key4k, "srv-1", "http://nas/movie.eng.ass", offsetMs = 0, updatedAtEpochMs = 0),
        )

        val recalled1080 = repository.recall(key1080)!!
        val recalled4k = repository.recall(key4k)!!
        assertEquals("http://nas/movie.zh.srt", recalled1080.subtitleId)
        assertEquals(1500L, recalled1080.offsetMs)
        assertEquals("http://nas/movie.eng.ass", recalled4k.subtitleId)
        assertEquals(0L, recalled4k.offsetMs)
    }

    @Test
    fun `different servers never share memory`() = runBlocking {
        val keyA = SubtitleMemoryKeys.forItem(item(serverId = "srv-a", sizeBytes = 9L))
        val keyB = SubtitleMemoryKeys.forItem(item(serverId = "srv-b", sizeBytes = 9L))
        assertTrue(keyA != keyB)
        repository.remember(
            SubtitleMemoryEntry(keyA, "srv-a", "sub-a", offsetMs = 0, updatedAtEpochMs = 0),
        )
        assertNull(repository.recall(keyB))
    }

    // ---- 回放 / 覆盖 / 遗忘 ----

    @Test
    fun `remember and recall roundtrip with timestamp filled by store clock`() = runBlocking {
        val key = SubtitleMemoryKeys.forItem(item(sizeBytes = 5L))
        repository.remember(
            SubtitleMemoryEntry(key, "srv-1", "sub.srt", offsetMs = -500, updatedAtEpochMs = 0),
        )
        val recalled = repository.recall(key)
        assertNotNull(recalled)
        assertEquals("sub.srt", recalled!!.subtitleId)
        assertEquals(-500L, recalled.offsetMs)
        assertTrue("仓库必须补齐时间戳", recalled.updatedAtEpochMs > 0)
    }

    @Test
    fun `later manual choice overwrites earlier memory`() = runBlocking {
        val key = SubtitleMemoryKeys.forItem(item(sizeBytes = 6L))
        repository.remember(
            SubtitleMemoryEntry(key, "srv-1", "old.srt", offsetMs = 0, updatedAtEpochMs = 0),
        )
        repository.remember(
            SubtitleMemoryEntry(key, "srv-1", "new.zh.ass", offsetMs = 250, updatedAtEpochMs = 0),
        )
        val recalled = repository.recall(key)!!
        assertEquals("new.zh.ass", recalled.subtitleId)
        assertEquals(250L, recalled.offsetMs)
    }

    @Test
    fun `forget removes only the targeted version`() = runBlocking {
        val key1 = SubtitleMemoryKeys.forItem(item(sizeBytes = 7L))
        val key2 = SubtitleMemoryKeys.forItem(item(sizeBytes = 8L))
        repository.remember(SubtitleMemoryEntry(key1, "srv-1", "a", 0, 0))
        repository.remember(SubtitleMemoryEntry(key2, "srv-1", "b", 0, 0))

        repository.forget(key1)

        assertNull(repository.recall(key1))
        assertEquals("b", repository.recall(key2)!!.subtitleId)
    }

    @Test
    fun `deleteByServer cascades with server removal`() = runBlocking {
        val keyA = SubtitleMemoryKeys.forItem(item(serverId = "srv-a", sizeBytes = 3L))
        val keyB = SubtitleMemoryKeys.forItem(item(serverId = "srv-b", sizeBytes = 3L))
        repository.remember(SubtitleMemoryEntry(keyA, "srv-a", "a", 0, 0))
        repository.remember(SubtitleMemoryEntry(keyB, "srv-b", "b", 0, 0))

        repository.deleteByServer("srv-a")

        assertNull(repository.recall(keyA))
        assertNotNull(repository.recall(keyB))
    }
}
