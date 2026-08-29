package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1C-2 Query Pipeline 领域模型测试：
 * 默认排序 / direction / RANDOM 无方向 / 兼容性 / capability 过滤。
 */
class MediaQueryTest {

    // ---- 默认排序 ----

    @Test
    fun `default query is server default sort with default page`() {
        val query = MediaListQuery()
        assertEquals(MediaSortField.SERVER_DEFAULT, query.sort.field)
        assertEquals(SortDirection.ASC, query.sort.direction)
        assertEquals(0, query.page.offset)
        assertEquals(50, query.page.limit)
    }

    @Test
    fun `media sort defaults to ascending`() {
        val sort = MediaSort(MediaSortField.DATE_ADDED)
        assertEquals(SortDirection.ASC, sort.direction)
        assertTrue(sort.hasDirection)
    }

    // ---- direction ----

    @Test
    fun `direction is preserved on copy`() {
        val sort = MediaSort(MediaSortField.COMMUNITY_RATING).copy(direction = SortDirection.DESC)
        assertEquals(SortDirection.DESC, sort.direction)
        assertEquals(MediaSortField.COMMUNITY_RATING, sort.field)
    }

    @Test
    fun `directed fields have direction semantics`() {
        val directed = listOf(
            MediaSortField.DATE_ADDED,
            MediaSortField.TITLE,
            MediaSortField.COMMUNITY_RATING,
            MediaSortField.CRITIC_RATING,
            MediaSortField.PRODUCTION_YEAR,
            MediaSortField.PREMIERE_DATE,
            MediaSortField.OFFICIAL_RATING,
            MediaSortField.RUNTIME,
            MediaSortField.BITRATE,
            MediaSortField.SIZE,
        )
        directed.forEach { field ->
            assertTrue("缺少方向语义: $field", MediaSort(field).hasDirection)
        }
    }

    // ---- RANDOM / SERVER_DEFAULT 不需要 direction ----

    @Test
    fun `random and server default are directionless`() {
        assertFalse(MediaSort(MediaSortField.RANDOM, SortDirection.DESC).hasDirection)
        assertFalse(MediaSort(MediaSortField.SERVER_DEFAULT, SortDirection.DESC).hasDirection)
    }

    // ---- 兼容性：PageRequest / PagedResult 语义不变 ----

    @Test
    fun `page request next advances by limit`() {
        val page = PageRequest(offset = 0, limit = 50).next
        assertEquals(50, page.offset)
        assertEquals(50, page.limit)
    }

    @Test
    fun `paged result keeps legacy defaults`() {
        val result = PagedResult(items = listOf("a"))
        assertNull(result.totalCount)
        assertTrue(result.hasMore)
        assertEquals(1, result.nextOffset)
    }

    @Test
    fun `media item discovery fields default to null`() {
        val item = MediaItem(serverId = "srv", id = "m1", type = MediaType.MOVIE, title = "Fargo")
        assertNull(item.dateAddedEpochMs)
        assertNull(item.criticRating)
        assertNull(item.premiereDateEpochMs)
        assertNull(item.officialRating)
        assertNull(item.bitrate)
        // 已有字段不重复、不受影响
        assertNull(item.communityRating)
        assertNull(item.sizeBytes)
    }

    // ---- capability filtering ----

    @Test
    fun `capabilities supports checks membership`() {
        val caps = MediaSortCapabilities(
            setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE, MediaSortField.COMMUNITY_RATING),
        )
        assertTrue(caps.supports(MediaSortField.TITLE))
        assertFalse(caps.supports(MediaSortField.BITRATE))
    }

    @Test
    fun `capabilities filter keeps caller order and drops unsupported`() {
        val caps = MediaSortCapabilities(setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE))
        val requested = listOf(
            MediaSortField.SERVER_DEFAULT,
            MediaSortField.CRITIC_RATING,
            MediaSortField.TITLE,
            MediaSortField.BITRATE,
        )
        assertEquals(listOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE), caps.filter(requested))
    }

    @Test
    fun `server default only is minimal capability`() {
        val caps = MediaSortCapabilities.SERVER_DEFAULT_ONLY
        assertTrue(caps.supports(MediaSortField.SERVER_DEFAULT))
        assertFalse(caps.supports(MediaSortField.RANDOM))
        assertEquals(listOf(MediaSortField.SERVER_DEFAULT), caps.filter(MediaSortField.entries.toList()))
    }
}
