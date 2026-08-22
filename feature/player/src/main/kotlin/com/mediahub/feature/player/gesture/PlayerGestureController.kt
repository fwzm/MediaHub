package com.mediahub.feature.player.gesture

import com.mediahub.model.PlayerGestures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 手势 seek 预览指示（scrub 共用）。 */
data class GestureSeekPreview(
    val targetPositionMs: Long,
    val deltaMs: Long,
)

/** 长按临时倍速指示。 */
data class GestureSpeedPreview(
    val speed: Float,
    /** 长按模式：true=快退（rewind），false=正向倍速。 */
    val isRewind: Boolean = false,
)

/**
 * 播放器手势状态机（U3-B revision，纯逻辑、无 Android/Compose 依赖、可单测）。
 *
 * - 单击 → Overlay 显隐；
 * - 双击 → 40/20/40 三区（左/中/右）：中区永远暂停/继续；左右区按偏好 seek 或回退暂停/继续；
 * - 水平拖动 → scrub 预览（松手 commit）；
 * - 长按 → 方向模式（偏好开启时左半屏 rewind / 右半屏正向倍速；关闭时两侧正向倍速）。
 *   初始倍率按 [PlayerGestures.longPressDefaultSpeed]；水平拖动沿阶梯调倍率；
 *   松手恢复长按前永久倍速。
 */
class PlayerGestureController(
    private val gestures: () -> PlayerGestures,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val currentSpeed: () -> Float,
    private val actions: Actions,
) {
    /** 手势动作出口（播放页接到 PlaybackEnginePort / Overlay 状态）。 */
    interface Actions {
        fun onOverlayToggle()
        fun onPlayPauseToggle()
        fun onPreviewSeek(positionMs: Long)
        fun onCommitSeek(positionMs: Long)
        fun onSpeedChange(speed: Float)
    }

    private val _scrubPreview = MutableStateFlow<GestureSeekPreview?>(null)
    val scrubPreview: StateFlow<GestureSeekPreview?> = _scrubPreview.asStateFlow()

    private val _speedPreview = MutableStateFlow<GestureSpeedPreview?>(null)
    val speedPreview: StateFlow<GestureSpeedPreview?> = _speedPreview.asStateFlow()

    private var scrubAnchorMs = 0L
    private var savedPermanentSpeed = 1f
    /** 长按 rewind 累计目标位置（tick 递减，COMMIT 直接用，不再减 step）。 */
    private var rewindTargetMs = 0L
    /** 长按方向模式：true=rewind，false=正向倍速。 */
    private var longPressIsRewind = false

    // ---- 单击 ----

    fun onTap() {
        actions.onOverlayToggle()
    }

    // ---- 双击：40% / 20% / 40% 三区 ----

    fun onDoubleTap(xFraction: Float) {
        val g = gestures()
        val backward = xFraction < DOUBLE_TAP_LEFT
        val forward = xFraction > DOUBLE_TAP_RIGHT
        val enabled = when {
            backward -> g.doubleTapSeekBackwardEnabled
            forward -> g.doubleTapSeekForwardEnabled
            else -> false // 中间 20% 永远暂停/继续
        }
        if (enabled) {
            val seconds = if (backward) g.doubleTapSeekBackwardSeconds else g.doubleTapSeekForwardSeconds
            val deltaMs = seconds * 1000L * if (backward) -1L else 1L
            actions.onCommitSeek(clampPosition(positionMs() + deltaMs))
        } else {
            // 未启用 seek 的区域（含中间 20%）→ 播放/暂停（不再受已删除的总开关控制）
            actions.onPlayPauseToggle()
        }
    }

    // ---- 水平拖动 scrub ----

    fun onScrubStart() {
        if (!gestures().scrubEnabled) return
        scrubAnchorMs = positionMs()
        _scrubPreview.value = GestureSeekPreview(scrubAnchorMs, 0)
    }

    fun onScrubDelta(cumulativeFraction: Float) {
        if (_scrubPreview.value == null) return
        val target = clampPosition(scrubAnchorMs + (cumulativeFraction * scrubSensitivityMs()).toLong())
        _scrubPreview.value = GestureSeekPreview(target, target - scrubAnchorMs)
    }

    fun onScrubEnd() {
        _scrubPreview.value?.let { actions.onCommitSeek(it.targetPositionMs) }
        _scrubPreview.value = null
    }

    fun onScrubCancel() {
        _scrubPreview.value = null
    }

    // ---- 长按方向倍速 ----

    /**
     * 长按锁定模式：偏好开启时左半屏=rewind、右半屏=正向倍速；关闭时两侧正向。
     * [xFraction] 为按下位置相对屏宽的比例（0..1）。
     */
    fun onSpeedActivate(xFraction: Float) {
        val g = gestures()
        if (!g.longPressSpeedEnabled) return
        savedPermanentSpeed = currentSpeed()
        longPressIsRewind = g.longPressDirectionalEnabled && xFraction < LONG_PRESS_SPLIT
        if (longPressIsRewind) rewindTargetMs = positionMs()
        val ladder = speedLadder(g)
        val entry = ladder.lastOrNull { it <= g.longPressDefaultSpeed } ?: ladder.first()
        _speedPreview.value = GestureSpeedPreview(entry, isRewind = longPressIsRewind)
        if (longPressIsRewind) {
            // rewind 模式：不调播放倍速，走 tick 节流 seek（手势层每 333ms 调用一次）
        } else {
            actions.onSpeedChange(entry)
        }
    }

    /**
     * 长按期间水平拖动调整倍率/rewind 速度。
     * - 正向倍速：沿阶梯调档位
     * - rewind：调整 rewind 速度系数（0.5×..4.0×），不调播放倍速
     */
    fun onSpeedDrag(cumulativeFraction: Float) {
        if (_speedPreview.value == null) return
        val g = gestures()
        val ladder = speedLadder(g)
        if (longPressIsRewind) {
            // rewind 与正向共用倍率阶梯（尊重 longPressSpeedMin 过滤）
            val entryIndex = ladder.indexOfLast { it <= g.longPressDefaultSpeed }.coerceAtLeast(0)
            val step = (cumulativeFraction / SPEED_STEP_FRACTION).roundToInt()
            val index = (entryIndex + step).coerceIn(0, ladder.lastIndex)
            _speedPreview.value = GestureSpeedPreview(ladder[index], isRewind = true)
        } else {
            val entryIndex = ladder.indexOfLast { it <= g.longPressDefaultSpeed }.coerceAtLeast(0)
            val step = (cumulativeFraction / SPEED_STEP_FRACTION).roundToInt()
            val index = (entryIndex + step).coerceIn(0, ladder.lastIndex)
            _speedPreview.value = GestureSpeedPreview(ladder[index], isRewind = false)
            actions.onSpeedChange(ladder[index])
        }
    }

    /** 松手 / 取消：恢复长按前的永久倍速。 */
    fun onSpeedEnd() {
        if (_speedPreview.value == null) return
        _speedPreview.value = null
        if (!longPressIsRewind) {
            actions.onSpeedChange(savedPermanentSpeed)
        }
    }

    // ---- rewind tick（由手势层周期性调用；仅 rewind 模式生效） ----

    fun onRewindTick() {
        if (_speedPreview.value?.isRewind != true) return
        val speed = _speedPreview.value!!.speed
        val stepMs = (REWIND_BASE_STEP_MS * speed).toLong()
        // 累计递减：不依赖 Media3/mpv 的异步 position 刷新，避免多 tick 读到同一旧值
        rewindTargetMs = clampPosition(rewindTargetMs - stepMs)
        actions.onPreviewSeek(rewindTargetMs)
    }

    /** 松手：直接 COMMIT 累计目标（不再减一步）。 */
    fun onRewindCommit() {
        if (_speedPreview.value?.isRewind != true) return
        actions.onCommitSeek(rewindTargetMs)
    }

    // ---- 计算 ----

    private fun scrubSensitivityMs(): Long {
        val raw = (durationMs() * SCRUB_DURATION_FRACTION).toLong()
        return raw.coerceIn(SCRUB_MIN_SENSITIVITY_MS, SCRUB_MAX_SENSITIVITY_MS)
    }

    private fun clampPosition(positionMs: Long): Long {
        val duration = durationMs()
        return if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
    }

    private fun speedLadder(g: PlayerGestures): List<Float> {
        val min = minOf(g.longPressSpeedMin, g.longPressSpeedMax)
        val max = maxOf(g.longPressSpeedMin, g.longPressSpeedMax)
        val ladder = BASE_SPEED_LADDER.filter { it in min..max }
        return ladder.ifEmpty { listOf(min) }
    }

    private companion object {
        /** 双击三区：左 40%、中 20%、右 40%。 */
        const val DOUBLE_TAP_LEFT = 0.4f
        const val DOUBLE_TAP_RIGHT = 0.6f

        /** 长按左右分屏：0.5 = 中点。 */
        const val LONG_PRESS_SPLIT = 0.5f

        const val SCRUB_DURATION_FRACTION = 0.1
        const val SCRUB_MIN_SENSITIVITY_MS = 60_000L
        const val SCRUB_MAX_SENSITIVITY_MS = 600_000L

        /** rewind 基础步长（1× 时约 333ms 回退 333ms → 净 1× 退速）。 */
        const val REWIND_BASE_STEP_MS = 333L

        /** rewind 拖动缩放：右拖 1 屏宽 = +1.0 系数。 */
        const val REWIND_DRAG_SCALE = 1.0f

        const val SPEED_STEP_FRACTION = 0.1f

        val BASE_SPEED_LADDER = listOf(
            0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 4.5f, 5f,
        )
    }
}