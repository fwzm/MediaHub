package com.mediahub.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTopBarTitleTest {

    @Test
    fun titlePrefersTheLoadedContentTitle() {
        assertEquals("星际漫游", detailTopBarTitle("星际漫游", "路由占位标题"))
        assertEquals("星际漫游", detailTopBarTitle("  星际漫游  ", "路由占位标题"))
    }

    @Test
    fun titleFallsBackToTheRouteArgumentWhenContentIsMissingOrBlank() {
        assertEquals("路由占位标题", detailTopBarTitle(null, "路由占位标题"))
        assertEquals("路由占位标题", detailTopBarTitle("   ", " 路由占位标题 "))
        assertEquals("", detailTopBarTitle(null, ""))
    }

    @Test
    fun titleEmphasisRisesAsTheBarCollapses() {
        val expanded = detailTopBarTitleAlpha(0f)
        val half = detailTopBarTitleAlpha(0.5f)
        val collapsed = detailTopBarTitleAlpha(1f)
        // 断言钉住具体数值，而不是引用常量本身：常量被改动时必须红
        assertEquals(0.72f, expanded, 0.0001f)
        assertEquals(1.0f, collapsed, 0.0001f)
        assertEquals(0.72f, DETAIL_TOPBAR_EXPANDED_ALPHA, 0.0001f)
        assertEquals(1.0f, DETAIL_TOPBAR_COLLAPSED_ALPHA, 0.0001f)
        assertTrue("展开态应当弱于折叠态", expanded < collapsed)
        assertTrue("中间态应当落在两端之间", half > expanded && half < collapsed)
        assertNotEquals(expanded, collapsed)
    }

    @Test
    fun titleEmphasisClampsOutOfRangeFractions() {
        // 只断言「越界值被夹到端点」，不重复钉具体常量，避免与常量测试互相牵连
        assertEquals(detailTopBarTitleAlpha(0f), detailTopBarTitleAlpha(-2f), 0.0001f)
        assertEquals(detailTopBarTitleAlpha(1f), detailTopBarTitleAlpha(3f), 0.0001f)
        assertEquals(detailTopBarTitleAlpha(1f), detailTopBarTitleAlpha(2f), 0.0001f)
    }

    @Test
    fun titleStaysOnASingleEllipsizedLine() {
        assertEquals(1, DETAIL_TOPBAR_TITLE_MAX_LINES)
    }
}
