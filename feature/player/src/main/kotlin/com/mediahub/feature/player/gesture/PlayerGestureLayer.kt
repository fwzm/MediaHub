package com.mediahub.feature.player.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放器统一手势层（U3-B revision）：放在渲染 Surface / 字幕层之上、控制层之下。
 *
 * 手势集（判定顺序：先到先得）：
 * 1. 快速单击 → [PlayerGestureController.onTap]（Overlay 显隐）；
 * 2. 快速双击（第二次快速抬起）→ [PlayerGestureController.onDoubleTap]（40/20/40 三区）；
 * 3. 水平拖动（越过 touch slop）→ scrub 预览，松手 COMMIT；
 * 4. 长按 → 方向临时倍速（左半屏 rewind / 右半屏正向倍速），水平拖动调倍率，
 *    rewind 模式每 333ms tick 一次 seek，松手 COMMIT。
 * 双击消歧：单击在双击窗口内不触发，Overlay 不闪烁。
 */
@Composable
fun PlayerGestureLayer(
    controller: PlayerGestureController,
    modifier: Modifier = Modifier,
    onBrightness: () -> Float = { 0.5f },
    onVolume: () -> Float = { 0.5f },
    onLevelEnd: (PlayerLevelKind, Float) -> Unit = { _, _ -> },
) {
    Box(
        modifier = modifier.pointerInput(controller, onBrightness, onVolume, onLevelEnd) {
            detectPlayerGestures(controller, onBrightness, onVolume, onLevelEnd)
        },
    )
}

private enum class GesturePhase { PENDING, TAP, SCRUB, LONG_PRESS, VERTICAL, IGNORE }

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPlayerGestures(
    controller: PlayerGestureController,
    onBrightness: () -> Float,
    onVolume: () -> Float,
    onLevelEnd: (PlayerLevelKind, Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.isConsumed) return@awaitEachGesture

    val startX = down.position.x
    val startY = down.position.y
    val width = size.width.toFloat().takeIf { it > 0f } ?: 1f
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop
    var lastX = startX

    // ---- 阶段 1：PENDING ----
    val phase1 = withTimeoutOrNull(longPressTimeout) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.changes.any { it.isConsumed }) return@withTimeoutOrNull GesturePhase.IGNORE
            val change = event.changes.firstOrNull { it.pressed } ?: event.changes.first()
            lastX = change.position.x
            val dx = lastX - startX
            val dy = change.position.y - startY
            if (event.changes.none { it.pressed }) return@withTimeoutOrNull GesturePhase.TAP
            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                // 方向锁定：一旦识别为竖向，后续横移不会变成 scrub
                return@withTimeoutOrNull if (abs(dx) > abs(dy)) GesturePhase.SCRUB else GesturePhase.VERTICAL
            }
        }
        @Suppress("UNREACHABLE_CODE")
        GesturePhase.IGNORE
    }
    val phase = phase1 ?: GesturePhase.LONG_PRESS

    when (phase) {
        GesturePhase.TAP -> handleTap(controller, width, startX, doubleTapTimeout)

        GesturePhase.SCRUB -> {
            controller.onScrubStart()
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull()
                    if (change != null) {
                        controller.onScrubDelta((change.position.x - startX) / width)
                    }
                    if (event.changes.none { it.pressed }) {
                        completed = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                controller.onScrubCancel()
                throw e
            }
            if (completed) controller.onScrubEnd()
        }

        GesturePhase.VERTICAL -> {
            val screenHeight = size.height.toFloat().takeIf { it > 0f } ?: 1f
            controller.onVerticalStart(startX / width, onBrightness(), onVolume())
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull()
                    if (change != null) {
                        controller.onVerticalDelta((startY - change.position.y) / screenHeight)
                    }
                    if (event.changes.none { it.pressed }) {
                        completed = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                controller.onVerticalCancel()
                throw e
            }
            val result = controller.onVerticalEnd()
            if (completed && result != null) onLevelEnd(result.kind, result.fraction)
        }

        GesturePhase.LONG_PRESS -> {
            // 长按 → 按初始位置锁定方向模式（左=rewind，右=正向倍速）
            controller.onSpeedActivate(startX / width)
            val anchorX = lastX
            try {
                while (true) {
                    val event = withTimeoutOrNull(REWIND_TICK_MS) { awaitPointerEvent() }
                    if (event != null) {
                        val change = event.changes.firstOrNull()
                        if (change != null) {
                            controller.onSpeedDrag((change.position.x - anchorX) / width)
                        }
                        if (event.changes.none { it.pressed }) break
                    } else {
                        // 超时 → rewind tick（正向倍速模式下为 no-op）
                        controller.onRewindTick()
                    }
                }
            } catch (e: CancellationException) {
                if (controller.speedPreview.value?.isRewind == true) controller.onRewindCommit()
                controller.onSpeedEnd()
                throw e
            }
            if (controller.speedPreview.value?.isRewind == true) controller.onRewindCommit()
            controller.onSpeedEnd()
        }

        GesturePhase.IGNORE, GesturePhase.PENDING -> Unit
    }
}

/**
 * 单击路径：双击窗口内等待第二根手指。
 * - 超时无第二次按下 → 单击（Overlay）；
 * - 第二次按下后快速抬起 → 双击（40/20/40 三区矩阵）。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleTap(
    controller: PlayerGestureController,
    width: Float,
    firstTapX: Float,
    doubleTapTimeout: Long,
) {
    val secondDownX = withTimeoutOrNull(doubleTapTimeout) {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.firstOrNull { it.pressed }
            if (pressed != null) return@withTimeoutOrNull pressed.position.x
            if (event.changes.isEmpty()) continue
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    if (secondDownX == null) {
        controller.onTap()
        return
    }

    // 第二次按下：等待快速抬起 → 双击
    val quickUp = withTimeoutOrNull(doubleTapTimeout) {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    if (quickUp == true) {
        controller.onDoubleTap(secondDownX / width)
    }
}

private const val REWIND_TICK_MS = 333L