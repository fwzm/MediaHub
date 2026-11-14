package com.mediahub.core.ui.nav

import android.graphics.RenderNode
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * 液态玻璃背板录制器（条目 29 收尾）。
 *
 * 本项目用的 Compose 1.7.6 尚没有公开的 `rememberGraphicsLayer`，但
 * `android.graphics.RenderNode` 从 API 29 起就是公开 API：这里把调用方内容层的绘制
 * 命令录进 RenderNode，导航栏再用 `Modifier.blur`（只有 API 31+ 才真模糊）绘制这块背板。
 *
 * 低于 API 31 时 [available] 恒为 false，调用方继续走「半透明 + 高光 + 描边」仿制方案，
 * 不拿低版本上空操作的 blur 冒充玻璃。
 */
class GlassBackdropRecorder {
    private val node: RenderNode? = createNode()
    private var recorded = false

    val available: Boolean get() = node != null

    fun capture(scope: ContentDrawScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val node = node ?: return
        val width = scope.size.width.toInt()
        val height = scope.size.height.toInt()
        if (width <= 0 || height <= 0) return
        node.setPosition(0, 0, width, height)
        val recordingCanvas = node.beginRecording()
        try {
            CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(recordingCanvas), scope.size) {
                scope.drawContent()
            }
            recorded = true
        } finally {
            node.endRecording()
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        node?.discardDisplayList()
        recorded = false
    }

    internal fun draw(scope: DrawScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val node = node ?: return
        if (!recorded) return
        scope.drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRenderNode(node) }
    }

    private companion object {
        fun createNode(): RenderNode? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderNode("mediahubGlassBackdrop")
            } else {
                null
            }
    }
}

/** 给内容层加一段「顺便录一份背板」的绘制，正常内容照旧绘制在屏幕上。 */
fun Modifier.glassBackdropSource(recorder: GlassBackdropRecorder): Modifier = drawWithContent {
    recorder.capture(this)
    drawContent()
}
