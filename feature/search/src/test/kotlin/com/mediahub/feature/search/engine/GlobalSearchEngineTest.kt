package com.mediahub.feature.search.engine

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 聚合搜索引擎（Phase 1C-1）：partial success / 稳定排序 / 超时 / 并发上限 / 取消传播。 */
class GlobalSearchEngineTest {

    private fun item(id: String) = MediaItem(
        serverId = "srv", id = id, type = MediaType.MOVIE, title = "冰血暴",
    )

    private fun target(
        serverId: String,
        serverName: String,
        block: suspend (String, PageRequest) -> PagedResult<MediaItem>,
    ) = SearchTarget(serverId, serverName, block)

    private fun ok(vararg ids: String): suspend (String, PageRequest) -> PagedResult<MediaItem> =
        { _, _ -> PagedResult(items = ids.map(::item), totalCount = ids.size, hasMore = false) }

    // ---- 空白 query ----

    @Test
    fun `blank query emits idle state without invoking targets`() = runTest {
        var invoked = 0
        val engine = GlobalSearchEngine()
        val t = target("s1", "予初") { _, _ ->
            invoked++
            PagedResult(emptyList())
        }

        val states = engine.search(listOf(t), "   ").toList()

        assertEquals(1, states.size)
        assertTrue(states[0].hits.isEmpty())
        assertFalse(states[0].isSearching)
        assertEquals(0, invoked)
    }

    // ---- Partial success：单服失败不吞其它结果 ----

    @Test
    fun `partial success - failed server surfaces error others stream hits`() = runTest {
        val engine = GlobalSearchEngine()
        val failed = target("s-bad", "坏服") { _, _ ->
            throw java.io.IOException("wire down")
        }
        val okA = target("s-a", "予初", ok("a1", "a2"))
        val okC = target("s-c", "墨云阁", ok("c1"))

        val states = engine.search(listOf(failed, okA, okC), "冰血暴").toList()
        val final = states.last()

        assertEquals(3, final.hits.size)
        // hits 带 serverName，且不被失败服务器影响
        assertEquals("予初", final.hits[0].serverName)
        assertEquals("墨云阁", final.hits[2].serverName)
        // 失败只进 errors，不影响其它服务器
        assertTrue(final.errors.containsKey("s-bad"))
        assertEquals(setOf("s-a", "s-bad", "s-c"), final.completedServers)
        assertTrue(final.searchingServers.isEmpty())
        assertFalse(final.isSearching)
    }

    // ---- 稳定排序：与完成顺序无关 ----

    @Test
    fun `hits keep target order regardless of completion order`() = runTest {
        val engine = GlobalSearchEngine()
        val slowFirst = target("t1", "慢服") { _, _ ->
            delay(500)
            PagedResult(items = listOf(item("t1-hit")), hasMore = false)
        }
        val fastSecond = target("t2", "快服", ok("t2-hit"))

        val states = engine.search(listOf(slowFirst, fastSecond), "q").toList()
        val final = states.last()

        assertEquals(listOf("t1-hit", "t2-hit"), final.hits.map { it.item.id })
        // 中间态：t2 已返回但 t1 未完成时，hits 只含 t2（仍保持 target 序）
        val mid = states.last { it.completedServers.contains("t2") && !it.completedServers.contains("t1") }
        assertEquals(listOf("t2-hit"), mid.hits.map { it.item.id })
    }

    // ---- 单服务器超时 ----

    @Test
    fun `per server timeout surfaces error without failing others`() = runTest {
        val engine = GlobalSearchEngine(perServerTimeoutMs = 1_000)
        val hangs = target("s-hang", "挂起服") { _, _ -> awaitCancellation() }
        val okB = target("s-ok", "予初", ok("b1"))

        val collector = async { engine.search(listOf(hangs, okB), "q").toList() }
        advanceTimeBy(5_000)
        val states = collector.await()
        val final = states.last()

        assertEquals("搜索超时", final.errors["s-hang"])
        assertEquals(listOf("b1"), final.hits.map { it.item.id })
        assertTrue(final.completedServers.contains("s-hang"))
        assertTrue(final.searchingServers.isEmpty())
    }

    // ---- 并发上限 ----

    @Test
    fun `concurrency is bounded to maxConcurrency`() = runTest {
        val engine = GlobalSearchEngine(maxConcurrency = 2)
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        fun slowTarget(id: String) = target(id, id) { _, _ ->
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { prev -> maxOf(prev, now) }
            delay(100)
            inFlight.decrementAndGet()
            PagedResult(items = emptyList())
        }
        val targets = listOf("s1", "s2", "s3", "s4").map(::slowTarget)

        engine.search(targets, "q").toList()

        assertTrue("峰值并发 ${peak.get()} 超过上限 2", peak.get() <= 2)
    }

    // ---- 取消传播：旧 query 被切换时在途搜索被取消，且不折叠成 error ----

    @Test
    fun `cancelling collection cancels in flight search without error emission`() = runTest {
        val engine = GlobalSearchEngine()
        val started = CompletableDeferred<Unit>()
        val hanging = target("s1", "予初") { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }

        val collected = mutableListOf<GlobalSearchState>()
        val job = launch(StandardTestDispatcher(testScheduler)) {
            engine.search(listOf(hanging), "q").collect { collected += it }
        }
        runCurrent()
        assertTrue(started.isCompleted)

        job.cancelAndJoin()

        // 取消不产生任何 error 折叠（CancellationException 必须传播而非映射为业务错误）
        assertTrue(collected.isNotEmpty())
        collected.forEach { state ->
            assertNull("取消不得折叠成 error", state.errors["s1"])
        }
    }

    // ---- 每个命中都带 serverName ----

    @Test
    fun `every hit carries its server name`() = runTest {
        val engine = GlobalSearchEngine()
        val a = target("s-a", "予初", ok("a1"))
        val b = target("s-b", "墨云阁", ok("b1", "b2"))

        val final = engine.search(listOf(a, b), "q").toList().last()

        assertEquals("予初", final.hits.first { it.item.id == "a1" }.serverName)
        assertEquals("墨云阁", final.hits.first { it.item.id == "b1" }.serverName)
        assertEquals("墨云阁", final.hits.first { it.item.id == "b2" }.serverName)
    }
}
