package com.mediahub.core.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuRepositoryTest {

    private class FakeSource(
        override val label: String = "fake",
        var result: List<DanmakuComment> = emptyList(),
        var error: Exception? = null,
    ) : DanmakuSource {
        var calls = 0

        override suspend fun fetch(query: DanmakuQuery): List<DanmakuComment> {
            calls++
            error?.let { throw it }
            return result
        }
    }

    private fun query(
        onlineEnabled: Boolean = true,
        forceRefresh: Boolean = false,
    ) = DanmakuQuery(
        itemKey = "server/item",
        seriesTitle = "Frieren",
        episodeTitle = "第1话",
        onlineEnabled = onlineEnabled,
        danDanPlay = DanDanPlayConfig(appId = "id", appSecret = "secret"),
        forceRefresh = forceRefresh,
    )

    private fun comment(id: String, timeMs: Long, text: String = id) = DanmakuComment(
        id = id,
        timeMs = timeMs,
        text = text,
    )

    @Test
    fun `disabled online source never fetches`() = runTest {
        val source = FakeSource(result = listOf(comment("a", 1)))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))

        val snapshot = repository.load(query(onlineEnabled = false))

        assertEquals(0, source.calls)
        assertTrue(snapshot.comments.isEmpty())
        assertNull(snapshot.error)
    }

    @Test
    fun `online result is cached per item`() = runTest {
        val source = FakeSource(result = listOf(comment("a", 2_000), comment("b", 1_000)))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))

        val first = repository.load(query())
        val second = repository.load(query())

        assertEquals(1, source.calls)
        assertEquals(listOf("b", "a"), first.comments.map { it.id })
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals("b", second.comments.first().id)
    }

    @Test
    fun `force refresh bypasses cache`() = runTest {
        val source = FakeSource(result = listOf(comment("a", 1_000)))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))

        repository.load(query())
        repository.load(query(forceRefresh = true))

        assertEquals(2, source.calls)
    }

    @Test
    fun `source failure is tolerated and does not throw`() = runTest {
        val source = FakeSource(error = IllegalStateException("network down"))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))

        val snapshot = repository.load(query())

        assertTrue(snapshot.comments.isEmpty())
        assertNotNull(snapshot.error)
        assertTrue(snapshot.error!!.contains("network down"))
    }

    @Test
    fun `local comment survives reload and is merged with online results`() = runTest {
        val source = FakeSource(result = listOf(comment("online", 3_000)))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))
        repository.load(query())

        repository.addLocalComment("server/item", comment("mine", 500, "本地弹幕"))
        val reloaded = repository.load(query())

        assertEquals(listOf("mine", "online"), reloaded.comments.map { it.id })
        assertEquals("本地弹幕", reloaded.comments.first().text)
    }

    @Test
    fun `local only load works with online disabled`() = runTest {
        val repository = DanmakuRepository(InMemoryDanmakuCache(), emptyList())
        repository.addLocalComment("server/item", comment("mine", 500))

        val snapshot = repository.load(query(onlineEnabled = false))

        assertEquals(1, snapshot.comments.size)
        assertEquals("mine", snapshot.comments.single().id)
    }

    @Test
    fun `duplicate ids from online and local are de-duplicated with local priority`() = runTest {
        val source = FakeSource(result = listOf(comment("same", 1_000, "online")))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))
        repository.load(query())
        repository.addLocalComment("server/item", comment("same", 1_000, "local"))

        val snapshot = repository.load(query())

        assertEquals(1, snapshot.comments.size)
        assertEquals("local", snapshot.comments.single().text)
    }

    @Test
    fun `missing credentials yields an honest error`() = runTest {
        val source = FakeSource(error = DanmakuSourceException("未配置 DanDanPlay AppId/AppSecret"))
        val repository = DanmakuRepository(InMemoryDanmakuCache(), listOf(source))

        val snapshot = repository.load(query())

        assertTrue(snapshot.comments.isEmpty())
        assertTrue(snapshot.error!!.contains("AppId"))
    }

    @Test
    fun `file cache survives a new repository instance`() = runTest {
        val dir = kotlin.io.path.createTempDirectory("danmaku-repo").toFile()
        val cache = FileDanmakuCache(dir, dispatcher = Dispatchers.Unconfined)
        val source = FakeSource(result = listOf(comment("a", 1_000)))
        DanmakuRepository(cache, listOf(source)).load(query())
        val secondSource = FakeSource(result = listOf(comment("b", 2_000)))

        val snapshot = DanmakuRepository(cache, listOf(secondSource)).load(query())

        assertEquals(0, secondSource.calls)
        assertTrue(snapshot.fromCache)
        assertEquals("a", snapshot.comments.single().id)
    }
}
