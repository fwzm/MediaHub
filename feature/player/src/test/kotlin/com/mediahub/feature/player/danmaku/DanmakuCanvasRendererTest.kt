package com.mediahub.feature.player.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 离屏渲染：不依赖真机，直接画到 Bitmap 上验证 alpha 与描边。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmakuCanvasRendererTest {

    private fun visual(x: Float) = DanmakuVisual(
        text = "DANMAKU",
        xPx = x,
        baselineYPx = 70f,
        textSizePx = 48f,
        colorArgb = 0xFFFFFFFF.toInt(),
        alpha = 1f,
        commentId = "a",
    )

    private fun render(
        visuals: List<DanmakuVisual>,
        style: DanmakuRenderStyle,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 120, Bitmap.Config.ARGB_8888)
        val commands = DanmakuDrawPlanner.plan(visuals, style)
        DanmakuCanvasRenderer().draw(Canvas(bitmap), commands)
        return bitmap
    }

    private fun visibleAlphas(bitmap: Bitmap): List<Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.map { (it ushr 24) and 0xFF }.filter { it > 0 }
    }

    @Test
    fun `low opacity text is rendered with a single capped alpha`() {
        val bitmap = render(
            listOf(visual(20f)),
            DanmakuRenderStyle(opacity = 0.25f, strokeWidthPx = 2f),
        )

        val alphas = visibleAlphas(bitmap)
        assertTrue("expected visible glyph pixels", alphas.isNotEmpty())
        assertTrue("alpha must be capped at 25%", alphas.max() <= 64)
        assertTrue("alpha must be non-zero", alphas.max() > 0)
    }

    @Test
    fun `thicker stroke adds outline pixels`() {
        val thin = visibleAlphas(
            render(listOf(visual(20f)), DanmakuRenderStyle(opacity = 1f, strokeWidthPx = 0f)),
        ).size
        val thick = visibleAlphas(
            render(listOf(visual(20f)), DanmakuRenderStyle(opacity = 1f, strokeWidthPx = 5f)),
        ).size

        assertTrue("stroke should increase ink: thin=$thin thick=$thick", thick > thin)
    }

    @Test
    fun `empty command list and zero opacity do not crash`() {
        val empty = render(emptyList(), DanmakuRenderStyle())
        assertEqualsNoVisiblePixels(empty)

        val transparent = render(
            listOf(visual(20f)),
            DanmakuRenderStyle(opacity = 0f, strokeWidthPx = 2f),
        )
        assertEqualsNoVisiblePixels(transparent)
    }

    private fun assertEqualsNoVisiblePixels(bitmap: Bitmap) {
        assertTrue(visibleAlphas(bitmap).isEmpty())
    }
}
