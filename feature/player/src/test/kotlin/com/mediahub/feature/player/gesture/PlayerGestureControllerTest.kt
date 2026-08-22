package com.mediahub.feature.player.gesture

import com.mediahub.model.PlayerGestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlayerGestureController 纯状态机测试（U3-B revision）。
 * 覆盖：双击 40/20/40 三区、scrub 灵敏度与 clamp、长按方向模式（rewind/speed）、
 * 默认倍率可配、rewind tick 与 COMMIT、永久倍速恢复。
 */
class PlayerGestureControllerTest {

    private class RecordingActions : PlayerGestureController.Actions {
        val calls = mutableListOf<String>()
        var positionMs = 600_000L
        var durationMs = 1_200_000L
        var speed = 1f
        var gestures = PlayerGestures()

        override fun onOverlayToggle() { calls += "overlay" }
        override fun onPlayPauseToggle() { calls += "playPause" }
        override fun onPreviewSeek(positionMs: Long) { calls += "previewSeek:$positionMs" }
        override fun onCommitSeek(positionMs: Long) { calls += "commitSeek:$positionMs" }
        override fun onSpeedChange(speed: Float) { calls += "speed:$speed" }
    }

    private fun controller(actions: RecordingActions) = PlayerGestureController(
        gestures = { actions.gestures },
        positionMs = { actions.positionMs },
        durationMs = { actions.durationMs },
        currentSpeed = { actions.speed },
        actions = actions,
    )

    // ---- 单击 ----

    @Test
    fun `单击触发 Overlay 切换`() {
        val actions = RecordingActions()
        controller(actions).onTap()
        assertEquals(listOf("overlay"), actions.calls)
    }

    // ---- 双击 40/20/40 三区 ----

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
    fun `双击中间 20 percent 永远暂停继续不触发 seek`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = true, doubleTapSeekForwardEnabled = true)
        }
        controller(actions).onDoubleTap(0.5f)
        assertEquals(listOf("playPause"), actions.calls)
    }

    @Test
    fun `双击左半屏未启用快退时回退到暂停继续`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(doubleTapSeekBackwardEnabled = false, doubleTapPlayPauseEnabled = true)
        }
        controller(actions).onDoubleTap(0.25f)
        assertEquals(listOf("playPause"), actions.calls)
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

    // ---- scrub ----

    @Test
    fun `scrub 灵敏度为总时长的10 percent 且松手 COMMIT`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(0.5f)
        assertEquals(660_000L, c.scrubPreview.value?.targetPositionMs)
        assertEquals(60_000L, c.scrubPreview.value?.deltaMs)
        c.onScrubEnd()
        assertEquals(listOf("commitSeek:660000"), actions.calls)
        assertNull(c.scrubPreview.value)
    }

    @Test
    fun `scrub 灵敏度下限 60 秒`() {
        val actions = RecordingActions().apply { durationMs = 300_000L; positionMs = 150_000L }
        val c = controller(actions)
        c.onScrubStart()
        c.onScrubDelta(1f)
        assertEquals(210_000L, c.scrubPreview.value?.targetPositionMs)
    }

    @Test
    fun `scrub 关闭时无预览无 COMMIT`() {
        val actions = RecordingActions().apply { gestures = PlayerGestures(scrubEnabled = false) }
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

    // ---- 长按方向模式 ----

    @Test
    fun `长按左半屏开启方向时进入 rewind 模式不调播放倍速`() {
        val actions = RecordingActions().apply { speed = 1.5f }
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        assertEquals(2.0f, c.speedPreview.value?.speed)
        assertTrue(c.speedPreview.value?.isRewind ?: false)
        // rewind 模式不调播放倍速
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `长按右半屏开启方向时进入正向倍速`() {
        val actions = RecordingActions().apply { speed = 1.5f }
        val c = controller(actions)
        c.onSpeedActivate(0.75f)
        assertEquals(2.0f, c.speedPreview.value?.speed)
        assertEquals(false, c.speedPreview.value?.isRewind)
        assertEquals(listOf("speed:2.0"), actions.calls)
    }

    @Test
    fun `方向关闭时两侧均正向倍速`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(longPressDirectionalEnabled = false)
        }
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        assertEquals(false, c.speedPreview.value?.isRewind)
        assertEquals(listOf("speed:2.0"), actions.calls)
    }

    @Test
    fun `长按默认倍率可配置`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(longPressDefaultSpeed = 3.0f)
        }
        val c = controller(actions)
        c.onSpeedActivate(0.75f)
        assertEquals(listOf("speed:3.0"), actions.calls)
    }

    @Test
    fun `松手恢复长按前永久倍速`() {
        val actions = RecordingActions().apply { speed = 1.5f }
        val c = controller(actions)
        c.onSpeedActivate(0.75f)
        c.onSpeedEnd()
        // 最后一条 call 是恢复永久倍速
        assertEquals("speed:1.5", actions.calls.last())
        assertNull(c.speedPreview.value)
    }

    @Test
    fun `rewind 模式拖动调整速度系数`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        assertEquals(2.0f, c.speedPreview.value?.speed)
        c.onSpeedDrag(0.5f)
        // 2.0 + 0.5 = 2.5，snap 到 0.25 步长
        assertEquals(2.5f, c.speedPreview.value?.speed)
        c.onSpeedDrag(-1.0f)
        // 2.0 - 1.0 = 1.0
        assertEquals(1.0f, c.speedPreview.value?.speed)
    }

    @Test
    fun `rewind tick 按系数节流后退`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        c.onSpeedDrag(0.25f) // 2.0 + 0.25 = 2.25 → snap 2.25
        // 2.25×, 基础步长 333ms: step = 333 * 2.25 ≈ 749ms
        c.onRewindTick()
        val expected = 600_000L - (333L * 2.25f).toLong()
        assertEquals("previewSeek:$expected", actions.calls.first())
    }

    @Test
    fun `rewind commit 提交最终位置`() {
        val actions = RecordingActions()
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        c.onRewindCommit()
        // 默认 2.0×, 基础步长 333ms: step = 333 * 2 = 666
        val expected = 600_000L - (333L * 2.0f).toLong()
        assertEquals("commitSeek:$expected", actions.calls.first())
    }

    @Test
    fun `长按倍速关闭时无任何动作`() {
        val actions = RecordingActions().apply {
            gestures = PlayerGestures(longPressSpeedEnabled = false)
        }
        val c = controller(actions)
        c.onSpeedActivate(0.25f)
        c.onSpeedDrag(0.3f)
        c.onSpeedEnd()
        assertNull(c.speedPreview.value)
        assertEquals(emptyList<String>(), actions.calls)
    }
}