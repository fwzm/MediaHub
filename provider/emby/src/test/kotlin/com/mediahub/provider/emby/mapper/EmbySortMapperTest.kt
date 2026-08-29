package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaSort
import com.mediahub.model.MediaSortField
import com.mediahub.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emby SortBy/SortOrder 映射（Phase 1C-2）：wire 表 / 方向 / 无方向语义 / 能力真实性。 */
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
    fun `capabilities only declare confirmed emby sortBy fields`() {
        val caps = EmbySortMapper.CAPABILITIES
        // 官方 GET /Users/{UserId}/Items 的 SortBy 枚举明确包含的九个
        val confirmed = setOf(
            MediaSortField.SERVER_DEFAULT,
            MediaSortField.DATE_ADDED,
            MediaSortField.TITLE,
            MediaSortField.COMMUNITY_RATING,
            MediaSortField.CRITIC_RATING,
            MediaSortField.PRODUCTION_YEAR,
            MediaSortField.PREMIERE_DATE,
            MediaSortField.RUNTIME,
            MediaSortField.RANDOM,
        )
        assertEquals(confirmed, caps.sortFields)
        // 未见于官方 SortBy 枚举：capability 隐藏（"响应有字段"≠"可作 SortBy"），
        // 恢复须经 per-server probe 拿到协议证据，不得静态全局开放
        assertFalse(caps.supportsSort(MediaSortField.OFFICIAL_RATING))
        assertFalse(caps.supportsSort(MediaSortField.BITRATE))
        assertFalse(caps.supportsSort(MediaSortField.SIZE))
        // Phase 1D 筛选：四项均为官方已文档化参数，全部声明
        assertEquals(
            setOf(
                com.mediahub.model.MediaFilterField.MEDIA_TYPE,
                com.mediahub.model.MediaFilterField.YEAR,
                com.mediahub.model.MediaFilterField.PLAYED,
                com.mediahub.model.MediaFilterField.FAVORITE,
            ),
            caps.filterFields,
        )
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
