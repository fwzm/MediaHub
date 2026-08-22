package com.mediahub.feature.player.gesture

import com.mediahub.model.PlayerGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PlayerGestureController 纯状态机测试（U3-B）。
 * 覆盖：双击矩阵、scrub 灵敏度与 clamp、长按倍速阶梯与永久倍速恢复、连续快退节流与 COMMIT。
 */
class PlayerGestureControllerTest {

    /** 记录型 Actions：按顺序捕获所有出口调用。 */
    private class RecordingActions : PlayerGestureController.Actions {
        val calls = mutableListOf<String>()
        var positionMs = 600_000L // 10 分钟处
        var durationMs = 1_200_000L // 20 分钟
        var speed = 1f
        var gestures = PlayerGestures()

        override fun onOverlayToggle() {
            calls += "overlay"
        }

        override fun onPlayPauseToggle() {
            calls += "playPause"
        }

        override fun onPreviewSeek(positionMs: Long) {
            calls += "previewSeek:$positionMs"
        }

        override fun onCommitSeek(positionMs: Long) {
            calls += "commitSeek:$positionMs"
        }

        override fun onSpeedChange(speed: Float) {
            calls += "speed:$speed"
        }
    }

    private fun controller(actions: RecordingActions) = PlayerGestureController(
        gestures = { actions.gestures },
        positionMs = { actions.positionMs },
        durationMs = { actions.durationMs },
        currentSpeed = { actions.speed },
        actions = actions,
    )

    // ---- 单击 / 双击矩阵 ----

    @Test
    fun `单击触发 Overlay 切换`() {
        val actions = RecordingActions()
        controller(actions).onTap()
        assertEquals(listOf("overlay"), actions.calls)
    }

    @Test
    fun `双击左半屏启用快退时 COMMIT 后退默认10秒`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = true)
        }
        controller(actions).onDoubleTap(0.25f)
        assertEquals(listOf("commitSeek:590000"), actions.calls)
    }

    @Test
    fun `双击右半屏启用快进时 COMMIT 前进自定义秒数`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekForwardEnabled = true, doubleTapSeekForwardSeconds = 30)
        }
        controller(actions).onDoubleTap(0.75f)
        assertEquals(listOf("commitSeek:630000"), actions.calls)
    }

    @Test
    fun `双击位置 clamp 到 0`() {
        val actions = RecordingActions().apply {
            positionMs = 3_000L
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = true)
        }
        controller(actions).onDoubleTap(0.25f)
        assertEquals(listOf("commitSeek:0"), actions.calls)
    }

    @Test
    fun `双击未启用快退侧且回退播放暂停开启时触发播放暂停`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(
                doubleTapSeekBackwardEnabled = false,
                doubleTapPlayPauseEnabled = true,
            )
        }
        controller(actions).onDoubleTap(0.25f)
        assertEquals(listOf("playPause"), actions.calls)
    }

    @Test
    fun `双击两侧均未启用且播放暂停关闭时无任何动作`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapPlayPauseEnabled = false)
        }
        controller(actions).onDoubleTap(0.25f)
        controller(actions).onDoubleTap(0.75f)
        assertEquals(emptyList<String>(), actions.calls)
    }

    // ---- scrub ----

    @Test
    fun `scrub 灵敏度为总时长的10 percent 且松手 COMMIT`() {
        // 20 分钟 × 10% = 2 分钟/屏
        val actions = RecordingActions()
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(0.5f) // 半屏 = +1 分钟
        assertEquals(660_000L, c.scrubPreview.value?.targetPositionMs)
        assertEquals(60_000L, c.scrubPreview.value?.deltaMs)
        c.onScrubEnd()
        assertEquals(listOf("commitSeek:660000"), actions.calls)
        assertNull(c.scrubPreview.value)
    }

    @Test
    fun `scrub 灵敏度下限 60 秒`() {
        // 5 分钟 × 10% = 30 秒 → clamp 到 60 秒；位置 2 分 30 秒处拖一屏 = +60 秒
        val actions = RecordingActions().apply {
            durationMs = 300_000L
            positionMs = 150_000L
        }
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(1f)
        assertEquals(210_000L, c.scrubPreview.value?.targetPositionMs)
    }

    @Test
    fun `scrub 灵敏度上限 10 分钟`() {
        // 3 小时 × 10% = 18 分钟 → clamp 到 10 分钟：位置 10 分钟处 -0.5 屏 = -5 分钟（未 clamp 应为 -9 分钟）
        val actions = RecordingActions().apply { durationMs = 10_800_000L }
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(-0.5f)
        assertEquals(300_000L, c.scrubPreview.value?.targetPositionMs)
    }

    @Test
    fun `scrub 目标位置 clamp 到 0 到 duration`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(10f)
        assertEquals(1_200_000L, c.scrubPreview.value?.targetPositionMs)
        c.onScrubEnd()
        assertEquals(listOf("commitSeek:1200000"), actions.calls)
    }

    @Test
    fun `scrub 关闭时无预览无 COMMIT`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(scrubEnabled = false)
        }
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(0.5f)
        c.onScrubEnd()
        assertNull(c.scrubPreview.value)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `scrub 取消丢弃预览不 COMMIT`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(0.5f)
        c.onScrubCancel()
        assertNull(c.scrubPreview.value)
        assertEquals(emptyList<String>(), actions.calls)
    }

    // ---- 连续快退 ----

    @Test
    fun `连续快退每 tick 节流 PREVIEW 后退 1 秒且松手 COMMIT 最终位置`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = true)
        }
        val c = controller(actions)
        c.onRewindHoldStart()
        c.onRewindTick()
        c.onRewindTick()
        c.onRewindTick()
        assertEquals(listOf("previewSeek:599000", "previewSeek:598000", "previewSeek:597000"), actions.calls)
        assertEquals(597_000L, c.rewindPreview.value?.targetPositionMs)
        c.onRewindHoldEnd()
        assertEquals("commitSeek:597000", actions.calls.last())
        assertNull(c.rewindPreview.value)
    }

    @Test
    fun `连续快退 clamp 到 0`() {
        val actions = RecordingActions().apply {
            positionMs = 1_500L
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = true)
        }
        val c = controller(actions)
        c.onRewindHoldStart()
        repeat(5) { c.onRewindTick() }
        assertEquals(0L, c.rewindPreview.value?.targetPositionMs)
        c.onRewindHoldEnd()
        assertEquals("commitSeek:0", actions.calls.last())
    }

    @Test
    fun `双击快退关闭时按住不产生任何动作`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = false)
        }
        val c = controller(actions)
        c.onRewindHoldStart()
        c.onRewindTick()
        c.onRewindHoldEnd()
        assertNull(c.rewindPreview.value)
        assertEquals(emptyList<String>(), actions.calls)
    }

    // ---- 长按临时倍速 ----

    @Test
    fun `长按进入入口档 2 倍速且松手恢复长按前永久倍速`() {
        val actions = RecordingActions().apply { speed = 1.5f }
        val c = controller(actions)
        c.onSpeedActivate()
        c.onSpeedEnd()
        assertEquals(listOf("speed:2.0", "speed:1.5"), actions.calls)
        assertNull(c.speedPreview.value)
    }

    @Test
    fun `长按拖动按阶梯调档且夹在偏好范围内`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onSpeedActivate()
        // +0.3 屏 = +3 档：2 → 3.5（阶梯 2, 2.5, 3, 3.5）
        c.onSpeedDrag(0.3f)
        assertEquals(3.5f, c.speedPreview.value?.speed)
        // 拖到极限不越界（上限 5×）
        c.onSpeedDrag(10f)
        assertEquals(5f, c.speedPreview.value?.speed)
        c.onSpeedEnd()
        assertEquals("speed:1.0", actions.calls.last())
    }

    @Test
    fun `长按下限 0 dot 1 时可拖到 0 dot 1 倍`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(longPressSpeedMin = 0.1f)
        }
        val c = controller(actions)
        c.onSpeedActivate()
        // -1.5 屏 = -15 档，从 2× 往下穿 0.1
        c.onSpeedDrag(-1.5f)
        assertEquals(0.1f, c.speedPreview.value?.speed)
    }

    @Test
    fun `长按倍速关闭时无任何动作`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(longPressSpeedEnabled = false)
        }
        val c = controller(actions)
        c.onSpeedActivate()
        c.onSpeedDrag(0.3f)
        c.onSpeedEnd()
        assertNull(c.speedPreview.value)
        assertEquals(emptyList<String>(), actions.calls)
    }
}
