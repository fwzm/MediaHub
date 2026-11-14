package com.mediahub.feature.player.danmaku

import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuLayoutEngineTest {

    private fun comment(
        id: String,
        timeMs: Long,
        text: String = id,
        mode: DanmakuMode = DanmakuMode.SCROLL,
        fontSizeSp: Int = 25,
    ) = DanmakuComment(id = id, timeMs = timeMs, text = text, mode = mode, fontSizeSp = fontSizeSp)

    private fun engine(
        width: Float = 1_000f,
        height: Float = 600f,
        safeTop: Float = 100f,
        safeBottom: Float = 500f,
        laneHeight: Float = 50f,
        speed: Float = 200f,
    ) = DanmakuLayoutEngine(
        config = DanmakuLayoutConfig(
            viewportWidthPx = width,
            viewportHeightPx = height,
            safeTopPx = safeTop,
            safeBottomPx = safeBottom,
            laneHeightPx = laneHeight,
            scrollSpeedPxPerSecond = speed,
            staticDurationMs = 4_000L,
        ),
        measureText = { text, _ -> text.length * 20f },
        textSizeForSp = { it * 1.6f },
    )

    @Test
    fun `scroll comment enters from right and leaves after computed duration`() {
        val engine = engine()
        engine.prepare(listOf(comment("a", 1_000L, "abcd")))

        val start = engine.frameAt(1_000L).single()
        assertEquals(1_000f, start.xPx, 0.01f)

        val later = engine.frameAt(2_000L).single()
        assertEquals(800f, later.xPx, 0.01f)

        // 宽度 80，屏幕 1000，速度 200px/s：完全离场 = 1000 + 5400ms
        assertTrue(engine.frameAt(6_399L).isNotEmpty())
        assertTrue(engine.frameAt(6_501L).isEmpty())
    }

    @Test
    fun `simultaneous comments use different lanes and never overlap vertically`() {
        val engine = engine()
        engine.prepare(
            listOf(
                comment("a", 1_000L),
                comment("b", 1_000L),
                comment("c", 1_000L),
            ),
        )

        val frame = engine.frameAt(1_100L)
        assertEquals(3, frame.size)
        val baselines = frame.map { it.baselineYPx }.distinct().sorted()
        assertEquals(3, baselines.size)
        assertTrue(baselines.zipWithNext().all { (first, second) -> second - first >= 49f })
    }

    @Test
    fun `comment is dropped when no lane is free instead of overlapping`() {
        val engine = engine(laneHeight = 50f, safeBottom = 150f)
        engine.prepare(
            listOf(
                comment("first", 0L, "abcdefghij"),
                comment("second", 100L, "abcdefghij"),
            ),
        )

        assertEquals(1, engine.frameAt(200L).size)
        assertEquals(1, engine.stats.dropped)
        assertEquals(2, engine.stats.total)
    }

    @Test
    fun `all lanes stay inside reserved safe area`() {
        val engine = engine()
        val comments = (0 until 20).map { comment("c$it", 1_000L + it) }
        engine.prepare(comments)

        val frame = engine.frameAt(1_200L)
        assertTrue(frame.isNotEmpty())
        assertTrue(frame.all { it.baselineYPx > 100f })
        assertTrue(frame.all { it.baselineYPx < 500f })
    }

    @Test
    fun `top and bottom comments are centered and expire after static duration`() {
        val engine = engine()
        engine.prepare(
            listOf(
                comment("top", 1_000L, "top", DanmakuMode.TOP),
                comment("bottom", 1_000L, "bottom", DanmakuMode.BOTTOM),
            ),
        )

        val frame = engine.frameAt(1_500L)
        assertEquals(2, frame.size)
        assertEquals(470f, frame.first { it.commentId == "top" }.xPx, 0.01f)
        assertTrue(frame.first { it.commentId == "top" }.baselineYPx < 200f)
        assertTrue(frame.first { it.commentId == "bottom" }.baselineYPx > 400f)
        assertTrue(engine.frameAt(5_100L).isEmpty())
    }

    @Test
    fun `seeking backwards produces the same frame as sequential playback`() {
        val engine = engine()
        engine.prepare(listOf(comment("a", 1_000L), comment("b", 3_000L)))

        val atTwo = engine.frameAt(2_000L).map { it.commentId }
        engine.frameAt(10_000L)
        val seekBack = engine.frameAt(2_000L).map { it.commentId }

        assertEquals(atTwo, seekBack)
        assertEquals(listOf("a"), seekBack)
    }

    @Test
    fun `invalid geometry and empty comments degrade to empty frames`() {
        val engine = engine(safeTop = 500f, safeBottom = 100f)
        engine.prepare(listOf(comment("a", 0L)))

        assertTrue(engine.frameAt(0L).isEmpty())
    }

    @Test
    fun `safe area reserves osd top bar and subtitle bottom region`() {
        val area = DanmakuSafeArea.forPlayer(1_000f)

        assertEquals(180f, area.topPx, 0.01f)
        assertEquals(700f, area.bottomPx, 0.01f)
        assertFalse(area.contains(100f))
        assertFalse(area.contains(800f))
        assertTrue(area.contains(400f))
    }

    @Test
    fun `duplicate and invalid comments are ignored`() {
        val engine = engine()
        engine.prepare(
            listOf(
                comment("a", 1_000L),
                comment("a", 1_000L),
                comment("b", -1L),
                comment("c", 1_000L, "   "),
            ),
        )

        assertEquals(1, engine.stats.placed)
        assertEquals(3, engine.stats.dropped)
        assertTrue(engine.frameAt(1_200L).all { it.commentId == "a" })
    }
}
