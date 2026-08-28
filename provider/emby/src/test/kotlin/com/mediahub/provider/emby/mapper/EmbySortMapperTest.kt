package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaSort
import com.mediahub.model.MediaSortField
import com.mediahub.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emby SortBy/SortOrder 映射（Phase 1C-2）：全字段映射 / 方向 / 无方向语义 / 能力自述。 */
class EmbySortMapperTest {

    @Test
    fun `maps all user facing sort fields to emby sortBy`() {
        val expected = mapOf(
            MediaSortField.DATE_ADDED to "DateCreated",
            MediaSortField.TITLE to "SortName",
            MediaSortField.COMMUNITY_RATING to "CommunityRating",
            MediaSortField.CRITIC_RATING to "CriticRating",
            MediaSortField.PRODUCTION_YEAR to "ProductionYear",
            MediaSortField.PREMIERE_DATE to "PremiereDate",
            MediaSortField.OFFICIAL_RATING to "OfficialRating",
            MediaSortField.RUNTIME to "Runtime",
            MediaSortField.BITRATE to "Bitrate",
            MediaSortField.SIZE to "Size",
            MediaSortField.RANDOM to "Random",
        )
        expected.forEach { (field, wire) -> assertEquals(wire, EmbySortMapper.sortBy(field)) }
    }

    @Test
    fun `server default maps to null sortBy`() {
        assertNull(EmbySortMapper.sortBy(MediaSortField.SERVER_DEFAULT))
    }

    @Test
    fun `maps direction to emby wire values`() {
        assertEquals(
            "Ascending",
            EmbySortMapper.sortOrder(MediaSort(MediaSortField.DATE_ADDED, SortDirection.ASC)),
        )
        assertEquals(
            "Descending",
            EmbySortMapper.sortOrder(MediaSort(MediaSortField.COMMUNITY_RATING, SortDirection.DESC)),
        )
    }

    @Test
    fun `directionless fields omit sortOrder`() {
        assertNull(EmbySortMapper.sortOrder(MediaSort(MediaSortField.RANDOM, SortDirection.DESC)))
        assertNull(EmbySortMapper.sortOrder(MediaSort(MediaSortField.SERVER_DEFAULT, SortDirection.ASC)))
    }

    @Test
    fun `capabilities declare all supported fields`() {
        val caps = EmbySortMapper.CAPABILITIES
        assertTrue(caps.supports(MediaSortField.SERVER_DEFAULT))
        assertTrue(caps.supports(MediaSortField.CRITIC_RATING))
        assertTrue(caps.supports(MediaSortField.BITRATE))
        assertTrue(caps.supports(MediaSortField.RANDOM))
        // 能力自述完整覆盖全部枚举（Emby 支持所有已定义字段）
        assertEquals(MediaSortField.entries.size, caps.fields.size)
    }

    @Test
    fun `emby date parser accepts variable fraction and garbage`() {
        assertEquals(1614844800000L, EmbyDateTimes.parseEpochMs("2021-03-04T08:00:00.0000000Z"))
        assertEquals(1614844800000L, EmbyDateTimes.parseEpochMs("2021-03-04T08:00:00Z"))
        assertNull(EmbyDateTimes.parseEpochMs("not-a-date"))
        assertNull(EmbyDateTimes.parseEpochMs(null))
        assertNull(EmbyDateTimes.parseEpochMs(""))
    }
}
