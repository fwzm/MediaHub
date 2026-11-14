package com.mediahub.feature.player.danmaku

import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuUiStateTest {

    @Test
    fun `count formatter groups digits without locale dependence`() {
        assertEquals("0", DanmakuCountFormatter.format(0))
        assertEquals("12", DanmakuCountFormatter.format(12))
        assertEquals("1,234", DanmakuCountFormatter.format(1_234))
        assertEquals("1,234,567", DanmakuCountFormatter.format(1_234_567))
        assertEquals("0", DanmakuCountFormatter.format(-5))
    }

    @Test
    fun `blank and whitespace-only text cannot be sent`() {
        assertFalse(DanmakuTextPolicy.validate("").canSend)
        assertFalse(DanmakuTextPolicy.validate("   ").canSend)
        assertEquals(DanmakuTextError.EMPTY, DanmakuTextPolicy.validate(" \n ").error)
    }

    @Test
    fun `text is trimmed and newlines collapse to spaces`() {
        val validation = DanmakuTextPolicy.validate("  hello\nworld\t ")

        assertTrue(validation.canSend)
        assertEquals("hello world", validation.normalized)
    }

    @Test
    fun `over long text is rejected`() {
        val validation = DanmakuTextPolicy.validate("x".repeat(DanmakuDefaults.MAX_TEXT_LENGTH + 1))

        assertFalse(validation.canSend)
        assertEquals(DanmakuTextError.TOO_LONG, validation.error)
    }

    @Test
    fun `snapshot maps counts cache flag and error`() {
        val comments = listOf(
            DanmakuComment(id = "1", timeMs = 1_000L, text = "a"),
            DanmakuComment(id = "2", timeMs = 2_000L, text = "b"),
        )
        val state = DanmakuUiState().withSnapshot(
            DanmakuSnapshot(
                comments = comments,
                fromCache = true,
                sourceLabel = "缓存",
                error = "网络失败",
            ),
        )

        assertEquals(2, state.count)
        assertEquals("2", state.countLabel)
        assertTrue(state.fromCache)
        assertEquals("缓存", state.sourceLabel)
        assertEquals("网络失败", state.notice)
        assertFalse(state.loading)
    }

    @Test
    fun `style setters clamp to safe ranges`() {
        assertEquals(DanmakuDefaults.MIN_OPACITY, DanmakuUiState().withOpacity(-1f).opacity, 0.0f)
        assertEquals(1f, DanmakuUiState().withOpacity(9f).opacity, 0.0f)
        assertEquals(0f, DanmakuUiState().withStrokeWidth(-4f).strokeWidthDp, 0.0f)
        assertEquals(
            DanmakuDefaults.MAX_STROKE_WIDTH_DP,
            DanmakuUiState().withStrokeWidth(99f).strokeWidthDp,
            0.0f,
        )
    }

    @Test
    fun `search results clear the searching flag and keep prior notice when no error`() {
        val state = DanmakuUiState(searching = true, notice = "old")
            .withSearchResults(emptyList())

        assertFalse(state.searching)
        assertEquals("old", state.notice)
        assertNull(DanmakuTextPolicy.validate("ok").error)
    }
}
