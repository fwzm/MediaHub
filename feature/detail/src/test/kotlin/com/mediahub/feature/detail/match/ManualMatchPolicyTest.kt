package com.mediahub.feature.detail.match

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动匹配页（条目 129 / 133）。
 *
 * 129 的「抽搐」根因是列表项高度随内容/状态变化：这里把行高、标题行数、
 * 请求条数三件事全部固化成常量 + 纯函数，实现层只允许照抄这些值。
 */
class ManualMatchPolicyTest {

    private fun item(
        year: Int? = null,
        type: MediaType = MediaType.MOVIE,
        title: String = "候选条目",
    ) = MediaItem(serverId = "s1", id = "i1", type = type, title = title, year = year)

    @Test
    fun `row geometry is fixed`() {
        assertEquals(76, MATCH_RESULT_ROW_HEIGHT_DP)
        assertEquals(2, MATCH_RESULT_TITLE_MAX_LINES)
        assertTrue(MATCH_RESULT_ROW_HEIGHT_DP > 0)
        assertTrue(MATCH_LIST_BOTTOM_PADDING_DP >= MATCH_RESULT_ROW_HEIGHT_DP)
    }

    @Test
    fun `request limit covers one screen with scroll room`() {
        // 800dp 可用高度：扣掉搜索区 168dp 后约 8 行，请求 16 条。
        assertEquals(16, matchRequestLimit(800f))
        // 极小屏保底 6 条，避免空列表抖动。
        assertEquals(6, matchRequestLimit(300f))
        assertEquals(6, matchRequestLimit(0f))
        assertEquals(6, matchRequestLimit(-100f))
        // 超大屏封顶 20 条，避免一次拉爆。
        assertEquals(20, matchRequestLimit(4000f))
    }

    @Test
    fun `layout constants do not drift when results change`() {
        // 同一设备尺寸下，无论结果条数是 0 还是 50，行高与请求条数都不变。
        val limit = matchRequestLimit(720f)
        assertEquals(limit, matchRequestLimit(720f))
        assertTrue(limit in 6..20)
    }

    @Test
    fun `candidate line merges year and type`() {
        assertEquals("2021 · 电影", matchCandidateLine(item(year = 2021)))
        assertEquals("剧集", matchCandidateLine(item(type = MediaType.SERIES)))
        assertEquals("2003 · 剧集", matchCandidateLine(item(year = 2003, type = MediaType.SERIES)))
    }

    @Test
    fun `blank query asks for input instead of searching`() {
        assertNull(matchQueryHint("流浪地球"))
        assertNull(matchQueryHint("  a  "))
        assertEquals("输入片名开始匹配", matchQueryHint(""))
        assertEquals("输入片名开始匹配", matchQueryHint("   "))
        assertEquals("输入片名开始匹配", matchQueryHint(null))
    }
}
