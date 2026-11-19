package com.mediahub.feature.search.engine

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #129 聚合搜索分页：首轮记录 nextOffset，loadMore 按服务器续拉并稳定追加。 */
class GlobalSearchPaginationTest {

    private fun item(id: String) = MediaItem(
        serverId = "srv", id = id, type = MediaType.MOVIE, title = id,
    )

    private fun target(
        serverId: String,
        block: suspend (String, PageRequest) -> PagedResult<MediaItem>,
    ) = SearchTarget(serverId, "服-$serverId", block)

    private fun page(
        items: List<MediaItem>,
        hasMore: Boolean,
        nextOffset: Int?,
    ) = PagedResult(items = items, totalCount = items.size, hasMore = hasMore, nextOffset = nextOffset)

    @Test
    fun `first page records only servers that have another page`() = runTest {
        val engine = GlobalSearchEngine()
        val more = target("s1") { _, _ -> page(listOf(item("a1")), hasMore = true, nextOffset = 1) }
        val exhausted = target("s2") { _, _ -> page(listOf(item("b1")), hasMore = false, nextOffset = null) }

        val final = engine.search(listOf(more, exhausted), "q").toList().last()

        assertEquals(mapOf("s1" to 1), final.nextOffsets)
        assertTrue(final.canLoadMore)
    }

    @Test
    fun `load more requests each next offset and appends hits in target order`() = runTest {
        val engine = GlobalSearchEngine()
        val callsA = mutableListOf<Int>()
        val callsB = mutableListOf<Int>()
        val a = target("s1") { _, page ->
            callsA += page.offset
            if (page.offset == 0) page(listOf(item("a1")), hasMore = true, nextOffset = 1)
            else page(listOf(item("a2")), hasMore = false, nextOffset = null)
        }
        val b = target("s2") { _, page ->
            callsB += page.offset
            if (page.offset == 0) page(listOf(item("b1")), hasMore = true, nextOffset = 1)
            else page(listOf(item("b2")), hasMore = false, nextOffset = null)
        }

        val first = engine.search(listOf(a, b), "q").toList().last()
        val final = engine.loadMore(listOf(a, b), first).toList().last()

        assertEquals(listOf(0, 1), callsA)
        assertEquals(listOf(0, 1), callsB)
        assertEquals(listOf("a1", "b1", "a2", "b2"), final.hits.map { it.item.id })
        assertTrue(final.nextOffsets.isEmpty())
        assertFalse(final.canLoadMore)
    }

    @Test
    fun `load more never calls a server already exhausted on first page`() = runTest {
        val engine = GlobalSearchEngine()
        var secondCalls = 0
        val more = target("s1") { _, page ->
            if (page.offset == 0) page(listOf(item("a1")), true, 1) else page(listOf(item("a2")), false, null)
        }
        val exhausted = target("s2") { _, _ ->
            secondCalls++
            page(listOf(item("b1")), false, null)
        }

        val first = engine.search(listOf(more, exhausted), "q").toList().last()
        val before = secondCalls
        engine.loadMore(listOf(more, exhausted), first).toList()

        assertEquals(before, secondCalls)
    }

    @Test
    fun `load more without a next offset emits the same state without invoking targets`() = runTest {
        val engine = GlobalSearchEngine()
        var calls = 0
        val done = target("s1") { _, _ ->
            calls++
            page(listOf(item("a1")), false, null)
        }

        val first = engine.search(listOf(done), "q").toList().last()
        val before = calls
        val states = engine.loadMore(listOf(done), first).toList()

        assertEquals(1, states.size)
        assertEquals(first.hits.map { it.item.id }, states[0].hits.map { it.item.id })
        assertEquals(before, calls)
    }

    @Test
    fun `next page failure keeps existing hits and surfaces error`() = runTest {
        val engine = GlobalSearchEngine()
        val flaky = target("s1") { _, page ->
            if (page.offset == 0) page(listOf(item("a1")), true, 1)
            else throw IOException("wire down")
        }

        val first = engine.search(listOf(flaky), "q").toList().last()
        val final = engine.loadMore(listOf(flaky), first).toList().last()

        assertEquals(listOf("a1"), final.hits.map { it.item.id })
        assertEquals("搜索失败", final.errors["s1"])
        assertTrue(final.nextOffsets.containsKey("s1"))
    }
}
