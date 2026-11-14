package com.mediahub.feature.player.danmaku

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.core.danmaku.DanmakuComment
import kotlinx.coroutines.isActive

object DanmakuTestTags {
    const val OVERLAY: String = "danmaku_overlay"
}

/**
 * 弹幕渲染层：叠加在视频画面上，位于字幕与 OSD 控制层之下。
 *
 * 平滑性（条目 96）：位置只在帧回调里写入一个 `Long` 状态，Canvas 的 draw lambda 读取它，
 * 因此每帧只触发绘制阶段失效，不触发整棵 Compose 重组。
 *
 * 透明度（条目 12）：透明度在 [DanmakuDrawPlanner] 中一次性换算为 paint alpha，
 * 不随帧变化，也不叠加在图层 alpha 上，避免低透明度下忽深忽浅。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: () -> Long,
    playing: Boolean,
    opacity: Float,
    strokeWidthDp: Float,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    safeArea: DanmakuSafeArea? = null,
) {
    BoxWithConstraints(modifier.testTag(DanmakuTestTags.OVERLAY)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints

        val density = LocalDensity.current
        val safe = safeArea ?: DanmakuSafeArea.forPlayer(heightPx)
        val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
        val textSizeForSp: (Int) -> Float = remember(density, fontScale) {
            { fontSizeSp -> with(density) { fontSizeSp.sp.toPx() } * fontScale }
        }
        val measuringPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        val measureText: (String, Float) -> Float = remember(measuringPaint) {
            { text, textSizePx ->
                measuringPaint.textSize = textSizePx
                measuringPaint.measureText(text)
            }
        }
        val laneHeightPx = textSizeForSp(DEFAULT_LANE_FONT_SP) * 1.4f
        val scrollSpeedPx = with(density) { SCROLL_SPEED_DP_PER_SECOND.dp.toPx() }

        val engine = remember(widthPx, heightPx, safe, laneHeightPx, scrollSpeedPx, comments, fontScale) {
            DanmakuLayoutEngine(
                config = DanmakuLayoutConfig(
                    viewportWidthPx = widthPx,
                    viewportHeightPx = heightPx,
                    safeTopPx = safe.topPx,
                    safeBottomPx = safe.bottomPx,
                    laneHeightPx = laneHeightPx,
                    scrollSpeedPxPerSecond = scrollSpeedPx,
                ),
                measureText = measureText,
                textSizeForSp = textSizeForSp,
            ).also { it.prepare(comments) }
        }

        var frameTimeMs by remember(engine) { mutableLongStateOf(positionMs()) }
        LaunchedEffect(engine, playing) {
            if (!playing) {
                frameTimeMs = positionMs()
                return@LaunchedEffect
            }
            while (isActive) {
                withFrameNanos {
                    frameTimeMs = positionMs()
                }
            }
        }

        val renderer = remember { DanmakuCanvasRenderer() }
        val style = remember(opacity, strokeWidthPx) {
            DanmakuRenderStyle(opacity = opacity, strokeWidthPx = strokeWidthPx)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val visuals = engine.frameAt(frameTimeMs)
                if (visuals.isNotEmpty()) {
                    renderer.draw(canvas.nativeCanvas, DanmakuDrawPlanner.plan(visuals, style))
                }
            }
        }
    }
}

private const val DEFAULT_LANE_FONT_SP = 25
private const val SCROLL_SPEED_DP_PER_SECOND = 140f
