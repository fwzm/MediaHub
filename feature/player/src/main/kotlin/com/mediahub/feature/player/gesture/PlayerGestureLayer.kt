package com.mediahub.feature.player.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放器统一手势层（U3-B）：替换播放区全屏 clickable。
 * 放在渲染 Surface / 字幕层之上、控制层之下——控制层自身的可点元素
 * 消费事件后不落到本层，空白区域落到本层。
 * 手势集（判定顺序：先到先得）：
 * 1. 快速单击 → [PlayerGestureController.onTap]（Overlay 显隐）；
 * 2. 快速双击（第二次快速抬起）→ [PlayerGestureController.onDoubleTap]；
 * 3. 双击左半屏后按住 → 连续快退（节流 PREVIEW tick，松手 COMMIT）；
 * 4. 水平拖动（越过 touch slop）→ scrub 预览，松手 COMMIT；
 * 5. 长按（无先前单击）→ 临时倍速，水平拖动调档，松开恢复。
 * 双击消歧：单击在双击窗口内不触发（自实现 tap 等待，等价
 * detectTapGestures 语义），Overlay 不闪烁。
 */
@Composable
fun PlayerGestureLayer(
    controller: PlayerGestureController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(controller) {
            detectPlayerGestures(controller)
        },
    )
}

/** 手势阶段。 */
private enum class GesturePhase { PENDING, TAP, SCRUB, LONG_PRESS, IGNORE }

/**
 * 自定义手势识别：awaitEachGesture 内自管 tap/double-tap/hold/drag/long-press
 * 判定（Compose 预制 detector 无法组合"双击后按住连续快退 + 长按倍速拖动"矩阵）。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPlayerGestures(
    controller: PlayerGestureController,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    // 事件已被上层控件（按钮/进度条）消费 → 本手势不参与
    if (down.isConsumed) return@awaitEachGesture

    val startX = down.position.x
    val startY = down.position.y
    val width = size.width.toFloat().takeIf { it > 0f } ?: 1f
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop
    var lastX = startX

    // ---- 阶段 1：PENDING —— 抬起(tap) / 位移(slop) / 超时(long-press) 三选一 ----
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
                return@withTimeoutOrNull if (abs(dx) > abs(dy)) GesturePhase.SCRUB else GesturePhase.IGNORE
            }
        }
        @Suppress("UNREACHABLE_CODE")
        GesturePhase.IGNORE
    }
    val phase = phase1 ?: GesturePhase.LONG_PRESS

    when (phase) {
        GesturePhase.TAP -> handleTap(controller, width, startX, doubleTapTimeout)

        GesturePhase.SCRUB -> {
            // 水平拖动 scrub：锚点 = 手势起点；拖动期间消费事件
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

        GesturePhase.LONG_PRESS -> {
            // 长按临时倍速：锚点 = 长按生效时的位置（按下瞬间锁定）
            controller.onSpeedActivate()
            val anchorX = lastX
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull()
                    if (change != null) {
                        controller.onSpeedDrag((change.position.x - anchorX) / width)
                    }
                    if (event.changes.none { it.pressed }) break
                }
            } catch (e: CancellationException) {
                controller.onSpeedEnd()
                throw e
            }
            controller.onSpeedEnd()
        }

        GesturePhase.IGNORE, GesturePhase.PENDING -> Unit
    }
}

/**
 * 单击路径：双击窗口内等待第二根手指。
 * - 超时无第二次按下 → 单击（Overlay）；
 * - 第二次按下后快速抬起 → 双击（左/右矩阵）；
 * - 第二次按下后持续按住 → 双击按住（左半屏连续快退；controller 按偏好门控）。
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
        // 双击窗口内无第二次按下 → 单击
        controller.onTap()
        return
    }

    // 第二次按下：等待快速抬起（双击）或持续按住（双击按住）
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
        return
    }

    // 双击后按住：连续快退 tick（controller 门控左半屏 + 偏好开关）
    controller.onRewindHoldStart()
    try {
        while (true) {
            val event = withTimeoutOrNull(REWIND_TICK_MS) { awaitPointerEvent() }
            if (event != null) {
                if (event.changes.none { it.pressed }) break
                // 按住期间的移动事件：忽略，不加速 tick（节流保持约每秒 3 次）
            } else {
                controller.onRewindTick()
            }
        }
    } finally {
        controller.onRewindHoldEnd()
    }
}

private const val REWIND_TICK_MS = 333L
