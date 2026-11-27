package com.mediahub.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuDrawPlannerTest {

    private val white = 0xFFFFFFFF.toInt()

    private fun visual(x: Float, color: Int = white) = DanmakuVisual(
        text = "DANMAKU",
        xPx = x,
        baselineYPx = 60f,
        textSizePx = 40f,
        colorArgb = color,
        alpha = 1f,
        commentId = "a",
    )

    private fun plan(
        visuals: List<DanmakuVisual>,
        style: DanmakuRenderStyle,
    ): DanmakuDrawPlan = DanmakuDrawPlanner.plan(visuals, style)

    @Test
    fun `low opacity is applied once as a layer alpha and never per overlap`() {
        val style = DanmakuRenderStyle(opacity = 0.25f, strokeWidthPx = 2f)
        val first = plan(listOf(visual(10f)), style)
        val second = plan(listOf(visual(250f)), style)

        assertEquals(0.25f, first.opacity, 0.0001f)
        assertEquals(first.opacity, second.opacity, 0.0001f)
        // 描边与填充保持不透明，同层合成时不会因重叠而叠加变深。
        val alphas = first.commands.map { (it.colorArgb ushr 24) and 0xFF }.distinct()
        assertEquals(listOf(255), alphas)
        assertTrue(first.commands.first().xPx != second.commands.first().xPx)
    }

    @Test
    fun `layer opacity never changes as the same comment moves across the screen`() {
        val style = DanmakuRenderStyle(opacity = 0.15f, strokeWidthPx = 1f)
        val opacities = listOf(0f, 100f, 400f, 900f).map { x ->
            plan(listOf(visual(x)), style).opacity
        }

        assertEquals(1, opacities.distinct().size)
        assertEquals(0.15f, opacities.first(), 0.0001f)
    }

    @Test
    fun `zero stroke width skips the outline command`() {
        val commands = plan(
            listOf(visual(10f)),
            DanmakuRenderStyle(opacity = 1f, strokeWidthPx = 0f),
        ).commands

        assertEquals(1, commands.size)
        assertEquals(0f, commands.single().strokeWidthPx, 0.0f)
    }

    @Test
    fun `stroke width is applied before the fill and clamped to a safe maximum`() {
        val commands = plan(
            listOf(visual(10f)),
            DanmakuRenderStyle(opacity = 1f, strokeWidthPx = 40f),
        ).commands

        assertEquals(2, commands.size)
        assertEquals(DanmakuRenderStyle.MAX_STROKE_WIDTH_PX, commands[0].strokeWidthPx, 0.01f)
        assertEquals(0f, commands[1].strokeWidthPx, 0.0f)
    }

    @Test
    fun `opacity is clamped`() {
        assertEquals(0f, plan(listOf(visual(10f)), DanmakuRenderStyle(opacity = -3f)).opacity, 0.0f)
        assertEquals(1f, plan(listOf(visual(10f)), DanmakuRenderStyle(opacity = 7f)).opacity, 0.0f)
    }

    @Test
    fun `empty input plans nothing`() {
        assertTrue(plan(emptyList(), DanmakuRenderStyle()).commands.isEmpty())
    }
}
