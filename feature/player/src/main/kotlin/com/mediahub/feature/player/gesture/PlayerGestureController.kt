package com.mediahub.feature.player.gesture

import com.mediahub.model.PlayerGestures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 手势 seek 预览指示（scrub / 连续快退共用）。 */
data class GestureSeekPreview(
    val targetPositionMs: Long,
    val deltaMs: Long,
)

/** 长按临时倍速指示。 */
data class GestureSpeedPreview(
    val speed: Float,
)

/**
 * 播放器手势状态机（U3-B，纯逻辑、无 Android/Compose 依赖、可单测）。
 *
 * Compose 手势层（PlayerGestureLayer）把原始指针事件归一为语义回调喂给本类；
 * 本类根据 [PlayerGestures] 偏好决定动作并维护预览指示状态：
 * - 单击 → Overlay 显隐；
 * - 双击 → 左右双击快退/快进（默认关），未启用侧回退为播放/暂停；
 * - 水平拖动 → scrub 预览（松手 commit）；
 * - 双击左侧后按住 → 连续快退（节流 PREVIEW seek，松手 COMMIT）；
 * - 长按 → 临时倍速（锚点在按下瞬间锁定，松开恢复长按前永久倍速）。
 *
 * 所有位置读取通过注入的 lambda（播放页传当前 state），保证测试可控。
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
    /** scrub 拖动预览（null = 未在拖动）。 */
    val scrubPreview: StateFlow<GestureSeekPreview?> = _scrubPreview.asStateFlow()

    private val _rewindPreview = MutableStateFlow<GestureSeekPreview?>(null)
    /** 连续快退累计预览（null = 未在快退）。 */
    val rewindPreview: StateFlow<GestureSeekPreview?> = _rewindPreview.asStateFlow()

    private val _speedPreview = MutableStateFlow<GestureSpeedPreview?>(null)
    /** 长按临时倍速预览（null = 未在倍速）。 */
    val speedPreview: StateFlow<GestureSpeedPreview?> = _speedPreview.asStateFlow()

    // ---- 内部锚点 ----
    private var scrubAnchorMs = 0L
    private var rewindStartMs = 0L
    private var rewindTargetMs = 0L
    private var savedPermanentSpeed = 1f

    // ---- 单击 / 双击 ----

    /** 单击：Overlay 显隐（始终生效）。 */
    fun onTap() {
        actions.onOverlayToggle()
    }

    /**
     * 双击矩阵：x 方向比例 < 0.5 为左半屏。
     * 启用侧 → 快退/快进（COMMIT）；未启用侧且双击播放/暂停开启 → 播放/暂停。
     */
    fun onDoubleTap(xFraction: Float) {
        val g = gestures()
        val backward = xFraction < LEFT_RIGHT_SPLIT
        val enabled = if (backward) g.doubleTapSeekBackwardEnabled else g.doubleTapSeekForwardEnabled
        if (enabled) {
            val seconds = if (backward) g.doubleTapSeekBackwardSeconds else g.doubleTapSeekForwardSeconds
            val deltaMs = seconds * 1000L * if (backward) -1L else 1L
            actions.onCommitSeek(clampPosition(positionMs() + deltaMs))
        } else if (g.doubleTapPlayPauseEnabled) {
            actions.onPlayPauseToggle()
        }
    }

    // ---- 水平拖动 scrub ----

    fun onScrubStart() {
        if (!gestures().scrubEnabled) return
        scrubAnchorMs = positionMs()
        _scrubPreview.value = GestureSeekPreview(scrubAnchorMs, 0)
    }

    /** [cumulativeFraction]：相对手势起点（锚点）的累计水平位移 / 屏宽。 */
    fun onScrubDelta(cumulativeFraction: Float) {
        if (_scrubPreview.value == null) return
        val target = clampPosition(scrubAnchorMs + (cumulativeFraction * scrubSensitivityMs()).toLong())
        _scrubPreview.value = GestureSeekPreview(target, target - scrubAnchorMs)
    }

    /** 松手：commit 预览目标位置。 */
    fun onScrubEnd() {
        _scrubPreview.value?.let { actions.onCommitSeek(it.targetPositionMs) }
        _scrubPreview.value = null
    }

    /** 手势被取消（协程取消等）：丢弃预览，不 commit。 */
    fun onScrubCancel() {
        _scrubPreview.value = null
    }

    // ---- 双击左侧后按住：连续快退 ----

    fun onRewindHoldStart() {
        if (!gestures().doubleTapSeekBackwardEnabled) return
        rewindStartMs = positionMs()
        rewindTargetMs = rewindStartMs
        _rewindPreview.value = GestureSeekPreview(rewindTargetMs, 0)
    }

    /**
     * 一次节流 tick（手势层约每秒 3 次调用）：
     * PREVIEW seek 一步（用 seek 而非负倍速——负倍速内核普遍不支持，
     * 且每步位置可精确控制在 [REWIND_STEP_MS]）。
     */
    fun onRewindTick() {
        if (_rewindPreview.value == null) return
        rewindTargetMs = (rewindTargetMs - REWIND_STEP_MS).coerceAtLeast(0)
        _rewindPreview.value = GestureSeekPreview(rewindTargetMs, rewindTargetMs - rewindStartMs)
        actions.onPreviewSeek(rewindTargetMs)
    }

    /** 松手：commit 最终位置。 */
    fun onRewindHoldEnd() {
        _rewindPreview.value?.let { actions.onCommitSeek(it.targetPositionMs) }
        _rewindPreview.value = null
    }

    // ---- 长按临时倍速 ----

    /** 长按生效：记住永久倍速，进入临时倍速（初始 [ENTRY_SPEED]）。 */
    fun onSpeedActivate() {
        if (!gestures().longPressSpeedEnabled) return
        savedPermanentSpeed = currentSpeed()
        val ladder = speedLadder()
        val entry = ladder.lastOrNull { it <= ENTRY_SPEED } ?: ladder.first()
        _speedPreview.value = GestureSpeedPreview(entry)
        actions.onSpeedChange(entry)
    }

    /**
     * 长按期间水平拖动（锚点 = 按下瞬间位置，由手势层传入累计比例）：
     * 阶梯档位 = 入口档 + round(累计位移 / 屏宽 / [SPEED_STEP_FRACTION])。
     */
    fun onSpeedDrag(cumulativeFraction: Float) {
        if (_speedPreview.value == null) return
        val ladder = speedLadder()
        val entryIndex = ladder.indexOfLast { it <= ENTRY_SPEED }.coerceAtLeast(0)
        val step = (cumulativeFraction / SPEED_STEP_FRACTION).roundToInt()
        val index = (entryIndex + step).coerceIn(0, ladder.lastIndex)
        val speed = ladder[index]
        _speedPreview.value = GestureSpeedPreview(speed)
        actions.onSpeedChange(speed)
    }

    /** 松手 / 取消：恢复长按前的永久倍速（而非 1.0×）。 */
    fun onSpeedEnd() {
        if (_speedPreview.value == null) return
        _speedPreview.value = null
        actions.onSpeedChange(savedPermanentSpeed)
    }

    // ---- 计算 ----

    /**
     * scrub 灵敏度（一屏拖动对应的时长）：
     * clamp(总时长 × 10%, 60s, 10min)。时长未知时取下限 60s。
     */
    private fun scrubSensitivityMs(): Long {
        val raw = (durationMs() * SCRUB_DURATION_FRACTION).toLong()
        return raw.coerceIn(SCRUB_MIN_SENSITIVITY_MS, SCRUB_MAX_SENSITIVITY_MS)
    }

    private fun clampPosition(positionMs: Long): Long {
        val duration = durationMs()
        return if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
    }

    /** 倍率阶梯（按偏好下限/上限过滤；空则退化为下限单档，避免无档可选）。 */
    private fun speedLadder(): List<Float> {
        val g = gestures()
        val min = minOf(g.longPressSpeedMin, g.longPressSpeedMax)
        val max = maxOf(g.longPressSpeedMin, g.longPressSpeedMax)
        val ladder = BASE_SPEED_LADDER.filter { it in min..max }
        return ladder.ifEmpty { listOf(min) }
    }

    private companion object {
        const val LEFT_RIGHT_SPLIT = 0.5f

        /** scrub：一屏拖动 = 总时长 10%，夹在 1-10 分钟。 */
        const val SCRUB_DURATION_FRACTION = 0.1
        const val SCRUB_MIN_SENSITIVITY_MS = 60_000L
        const val SCRUB_MAX_SENSITIVITY_MS = 600_000L

        /** 连续快退：每 tick（约 333ms）退 1s → 净退速约 3×。 */
        const val REWIND_STEP_MS = 1_000L

        /** 长按临时倍速入口档。 */
        const val ENTRY_SPEED = 2f

        /** 倍速阶梯步长：屏宽的 1/10 拖动 = 一档。 */
        const val SPEED_STEP_FRACTION = 0.1f

        val BASE_SPEED_LADDER = listOf(
            0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 4.5f, 5f,
        )
    }
}
