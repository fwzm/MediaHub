package com.mediahub.core.database.repository

import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import kotlinx.coroutines.flow.first
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
 * 搜索历史（条目 52/120）：持久化 / 去重 / 上限 / 单条删除。
 *
 * 使用 Room 内存库 + 可注入时钟，保证「同毫秒内写入」也能稳定断言先后顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryRepositoryTest {

    private lateinit var db: AppDatabase
    private var now = 1_000L

    private fun repository() = SearchHistoryRepository(db, clock = { now++ })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `record and observe newest first`() = runBlocking {
        val repository = repository()
        repository.record("冰血暴")
        repository.record("fargo")

        assertEquals(listOf("fargo", "冰血暴"), repository.observe().first())
    }

    @Test
    fun `duplicate query is deduplicated and moved to top`() = runBlocking {
        val repository = repository()
        repository.record("冰血暴")
        repository.record("fargo")
        repository.record("冰血暴")

        assertEquals(listOf("冰血暴", "fargo"), repository.observe().first())
    }

    @Test
    fun `blank query is ignored and surrounding spaces are trimmed`() = runBlocking {
        val repository = repository()
        repository.record("   ")
        repository.record("  fargo  ")

        assertEquals(listOf("fargo"), repository.observe().first())
    }

    @Test
    fun `history is capped and the oldest entry is evicted`() = runBlocking {
        val repository = repository()
        repeat(SearchHistoryRepository.MAX_ENTRIES + 3) { index ->
            repository.record("query-$index")
        }

        val history = repository.observe().first()
        assertEquals(SearchHistoryRepository.MAX_ENTRIES, history.size)
        assertEquals("query-${SearchHistoryRepository.MAX_ENTRIES + 2}", history.first())
        assertEquals("query-3", history.last())
    }

    @Test
    fun `remove deletes only the targeted entry and keeps the rest`() = runBlocking {
        val repository = repository()
        repository.record("冰血暴")
        repository.record("fargo")

        repository.remove("冰血暴")

        assertEquals(listOf("fargo"), repository.observe().first())
    }

    @Test
    fun `history survives a new repository instance over the same database`() = runBlocking {
        repository().record("冰血暴")

        assertEquals(listOf("冰血暴"), repository().observe().first())
    }

    @Test
    fun `recorded history is observable through the store interface`() = runBlocking {
        val store: SearchHistoryStore = repository()
        store.record("fargo")

        assertEquals(listOf("fargo"), store.observe().first())
    }
}
