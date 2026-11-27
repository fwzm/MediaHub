package com.mediahub.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 渲染样式。透明度只在这里统一换算成 paint alpha：
 * 同一条弹幕在整个滚动过程中 alpha 保持不变，避免低透明度下忽深忽浅（条目 12）。
 */
data class DanmakuRenderStyle(
    val opacity: Float = 1f,
    val strokeWidthPx: Float = 2f,
    val strokeColorArgb: Int = 0xFF000000.toInt(),
) {
    companion object {
        const val MAX_STROKE_WIDTH_PX: Float = 12f
    }
}

/** 一条绘制指令：描边先画、填充后画，保证文字主体清晰。 */
data class DanmakuDrawCommand(
    val text: String,
    val xPx: Float,
    val baselineYPx: Float,
    val textSizePx: Float,
    val colorArgb: Int,
    val strokeWidthPx: Float = 0f,
)

/** 一次绘制计划：描边/填充保持不透明，透明度作为整层 alpha 一次性合成。 */
data class DanmakuDrawPlan(
    val commands: List<DanmakuDrawCommand>,
    val opacity: Float,
)

/** 把布局结果转成绘制指令；纯 Kotlin，可在 JVM 单测中验证 alpha 与描边策略。 */
object DanmakuDrawPlanner {

    fun plan(
        visuals: List<DanmakuVisual>,
        style: DanmakuRenderStyle,
    ): DanmakuDrawPlan {
        val opacity = style.opacity.coerceIn(0f, 1f)
        if (visuals.isEmpty()) return DanmakuDrawPlan(emptyList(), opacity)
        val strokeWidth = style.strokeWidthPx.coerceIn(0f, DanmakuRenderStyle.MAX_STROKE_WIDTH_PX)
        val commands = ArrayList<DanmakuDrawCommand>(visuals.size * 2)
        visuals.forEach { visual ->
            if (strokeWidth > 0f) {
                commands += DanmakuDrawCommand(
                    text = visual.text,
                    xPx = visual.xPx,
                    baselineYPx = visual.baselineYPx,
                    textSizePx = visual.textSizePx,
                    colorArgb = withOpacity(style.strokeColorArgb, visual.alpha.coerceIn(0f, 1f)),
                    strokeWidthPx = strokeWidth,
                )
            }
            commands += DanmakuDrawCommand(
                text = visual.text,
                xPx = visual.xPx,
                baselineYPx = visual.baselineYPx,
                textSizePx = visual.textSizePx,
                colorArgb = withOpacity(visual.colorArgb, visual.alpha.coerceIn(0f, 1f)),
            )
        }
        return DanmakuDrawPlan(commands = commands, opacity = opacity)
    }

    internal fun withOpacity(colorArgb: Int, opacity: Float): Int {
        val alpha = (((colorArgb ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(0, 255)
        return (alpha shl 24) or (colorArgb and 0x00FFFFFF)
    }
}

/**
 * Android Canvas 落笔器；离屏 Bitmap 与 Compose Canvas 共用这一份绘制逻辑。
 *
 * 低透明度下先把描边+填充画进一个图层，再对整个图层统一施加 alpha，
 * 这样描边与填充重叠处不会二次叠加变深（条目 12 的闪烁根因）。
 */
class DanmakuCanvasRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.DEFAULT_BOLD
    }

    fun draw(canvas: Canvas, plan: DanmakuDrawPlan) {
        if (plan.commands.isEmpty() || plan.opacity <= 0f) return
        val layer = if (plan.opacity < 0.999f) {
            canvas.saveLayerAlpha(
                0f,
                0f,
                canvas.width.toFloat(),
                canvas.height.toFloat(),
                (plan.opacity * 255f).roundToInt().coerceIn(0, 255),
            )
        } else {
            -1
        }
        try {
            plan.commands.forEach { command ->
                val paint = if (command.strokeWidthPx > 0f) strokePaint else fillPaint
                paint.textSize = command.textSizePx
                paint.color = command.colorArgb
                if (command.strokeWidthPx > 0f) {
                    paint.strokeWidth = command.strokeWidthPx
                }
                canvas.drawText(command.text, command.xPx, command.baselineYPx, paint)
            }
        } finally {
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }
}
