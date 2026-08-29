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
        val caps = MediaQueryCapabilities(
            sortFields = setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE, MediaSortField.COMMUNITY_RATING),
            filterFields = setOf(MediaFilterField.MEDIA_TYPE),
        )
        assertTrue(caps.supportsSort(MediaSortField.TITLE))
        assertFalse(caps.supportsSort(MediaSortField.BITRATE))
        assertTrue(caps.supportsFilter(MediaFilterField.MEDIA_TYPE))
        assertFalse(caps.supportsFilter(MediaFilterField.YEAR))
    }

    @Test
    fun `capabilities filter keeps caller order and drops unsupported`() {
        val caps = MediaQueryCapabilities(sortFields = setOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE))
        val requested = listOf(
            MediaSortField.SERVER_DEFAULT,
            MediaSortField.CRITIC_RATING,
            MediaSortField.TITLE,
            MediaSortField.BITRATE,
        )
        assertEquals(listOf(MediaSortField.SERVER_DEFAULT, MediaSortField.TITLE), caps.filterSortFields(requested))
    }

    @Test
    fun `server default only is minimal capability`() {
        val caps = MediaQueryCapabilities.SERVER_DEFAULT_ONLY
        assertTrue(caps.supportsSort(MediaSortField.SERVER_DEFAULT))
        assertFalse(caps.supportsSort(MediaSortField.RANDOM))
        assertFalse(caps.supportsFilter(MediaFilterField.MEDIA_TYPE))
        assertEquals(listOf(MediaSortField.SERVER_DEFAULT), caps.filterSortFields(MediaSortField.entries.toList()))
    }

    // ---- MediaFilter（Phase 1D） ----

    @Test
    fun `media filter default is all-null and isDefault`() {
        val filter = MediaFilter()
        assertNull(filter.mediaType)
        assertNull(filter.year)
        assertNull(filter.played)
        assertNull(filter.favorite)
        assertTrue(filter.isDefault)
    }

    @Test
    fun `media filter with any value is not default`() {
        assertFalse(MediaFilter(mediaType = MediaType.SERIES).isDefault)
        assertFalse(MediaFilter(year = 2024).isDefault)
        assertFalse(MediaFilter(played = false).isDefault)
        assertFalse(MediaFilter(favorite = true).isDefault)
    }

    @Test
    fun `media filter tri-state values are preserved`() {
        val filter = MediaFilter(played = false, favorite = false)
        assertEquals(false, filter.played)
        assertEquals(false, filter.favorite)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `media filter rejects non positive year`() {
        MediaFilter(year = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `media filter rejects negative year`() {
        MediaFilter(year = -1)
    }

    @Test
    fun `media list query carries filter with default`() {
        val query = MediaListQuery()
        assertTrue(query.filter.isDefault)
        val filtered = query.copy(filter = MediaFilter(mediaType = MediaType.MOVIE, year = 2024))
        assertEquals(MediaType.MOVIE, filtered.filter.mediaType)
        assertEquals(2024, filtered.filter.year)
        // 原 query 不变（immutable）
        assertTrue(query.filter.isDefault)
    }
}
