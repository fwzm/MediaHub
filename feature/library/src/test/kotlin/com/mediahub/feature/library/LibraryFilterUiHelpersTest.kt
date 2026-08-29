package com.mediahub.feature.library

import com.mediahub.model.MediaFilter
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 筛选面板纯函数：年份草稿提交语义 + 标签（Phase 1D C2）。 */
class LibraryFilterUiHelpersTest {

    // ---- yearDraftToFilter：只有空串（清除）或恰好四位合法年份才提交 ----

    @Test
    fun `empty draft clears year`() {
        val result = yearDraftToFilter(MediaFilter(year = 2024), "")
        assertEquals(MediaFilter(), result)
    }

    @Test
    fun `empty draft with no existing year is no-op`() {
        assertNull(yearDraftToFilter(MediaFilter(), ""))
    }

    @Test
    fun `partial draft never commits`() {
        val current = MediaFilter()
        assertNull(yearDraftToFilter(current, "2"))
        assertNull(yearDraftToFilter(current, "20"))
        assertNull(yearDraftToFilter(current, "202"))
    }

    @Test
    fun `four digit draft commits year`() {
        assertEquals(MediaFilter(year = 2024), yearDraftToFilter(MediaFilter(), "2024"))
    }

    @Test
    fun `same year draft is no-op`() {
        assertNull(yearDraftToFilter(MediaFilter(year = 2024), "2024"))
    }

    @Test
    fun `non digit draft never commits`() {
        assertNull(yearDraftToFilter(MediaFilter(), "20a4"))
        assertNull(yearDraftToFilter(MediaFilter(), "abcd"))
    }

    @Test
    fun `zero year draft is rejected without crashing`() {
        // P1 回归："0000" 是四位数字草稿但违反 domain require(year>0)；
        // helper 必须按 no-op 丢弃（返回 null），绝不能抛 IllegalArgumentException 炸 UI
        val result = yearDraftToFilter(MediaFilter(), "0000")
        assertNull(result)
    }

    @Test
    fun `year draft violating domain invariant never throws`() {
        // 逐位输入路径下的任何四位组合都不允许抛异常（UI 事件路径无 try-catch）
        for (i in 0..9999) {
            val draft = i.toString().padStart(4, '0')
            try {
                yearDraftToFilter(MediaFilter(), draft)
            } catch (e: IllegalArgumentException) {
                throw AssertionError("draft=$draft 抛出异常", e)
            }
        }
    }

    @Test
    fun `other filter fields survive year commit`() {
        val current = MediaFilter(mediaType = MediaType.MOVIE, played = false)
        val result = yearDraftToFilter(current, "1998")
        assertEquals(MediaFilter(mediaType = MediaType.MOVIE, year = 1998, played = false), result)
    }

    // ---- 标签 ----

    @Test
    fun `labels cover tri states`() {
        assertEquals("全部", mediaTypeFilterLabel(null))
        assertEquals("电影", mediaTypeFilterLabel(MediaType.MOVIE))
        assertEquals("剧集", mediaTypeFilterLabel(MediaType.SERIES))
        assertEquals("单集", mediaTypeFilterLabel(MediaType.EPISODE))

        assertEquals("全部", playedFilterLabel(null))
        assertEquals("已看", playedFilterLabel(true))
        assertEquals("未看", playedFilterLabel(false))

        assertEquals("全部", favoriteFilterLabel(null))
        assertEquals("已收藏", favoriteFilterLabel(true))
        assertEquals("未收藏", favoriteFilterLabel(false))
    }
}
