package com.mediahub.feature.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势分区规则（缺陷 34 / 41）。
 *
 * 缺陷 34：双击屏幕左右边缘显隐控制栏必须作为**独立分区**接入，
 * 不能挤掉原有的 40/20/40 快退/播放/快进分区。
 * 规则：开关开启时最外侧 10% 为显隐区；10%-40% / 40%-60% / 60%-90% 保持原语义。
 *
 * 缺陷 41：从底部系统手势区起始的上滑必须让给系统（返回桌面），
 * 不能再被播放器解释为亮度/音量竖滑。
 */
class PlayerGestureZonePolicyTest {

    @Test
    fun `关闭边缘双击时保持原来的40-20-40分区`() {
        assertEquals(DoubleTapZone.SEEK_BACKWARD, PlayerGestureZonePolicy.doubleTapZone(0.05f, false))
        assertEquals(DoubleTapZone.SEEK_BACKWARD, PlayerGestureZonePolicy.doubleTapZone(0.25f, false))
        assertEquals(DoubleTapZone.PLAY_PAUSE, PlayerGestureZonePolicy.doubleTapZone(0.40f, false))
        assertEquals(DoubleTapZone.PLAY_PAUSE, PlayerGestureZonePolicy.doubleTapZone(0.50f, false))
        assertEquals(DoubleTapZone.PLAY_PAUSE, PlayerGestureZonePolicy.doubleTapZone(0.60f, false))
        assertEquals(DoubleTapZone.SEEK_FORWARD, PlayerGestureZonePolicy.doubleTapZone(0.75f, false))
        assertEquals(DoubleTapZone.SEEK_FORWARD, PlayerGestureZonePolicy.doubleTapZone(0.95f, false))
    }

    @Test
    fun `开启边缘双击后只有最外侧10 percent让位给显隐`() {
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_LEFT, PlayerGestureZonePolicy.doubleTapZone(0.00f, true))
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_LEFT, PlayerGestureZonePolicy.doubleTapZone(0.09f, true))
        assertEquals(DoubleTapZone.SEEK_BACKWARD, PlayerGestureZonePolicy.doubleTapZone(0.10f, true))
        assertEquals(DoubleTapZone.SEEK_BACKWARD, PlayerGestureZonePolicy.doubleTapZone(0.25f, true))
        assertEquals(DoubleTapZone.PLAY_PAUSE, PlayerGestureZonePolicy.doubleTapZone(0.50f, true))
        assertEquals(DoubleTapZone.SEEK_FORWARD, PlayerGestureZonePolicy.doubleTapZone(0.75f, true))
        assertEquals(DoubleTapZone.SEEK_FORWARD, PlayerGestureZonePolicy.doubleTapZone(0.90f, true))
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_RIGHT, PlayerGestureZonePolicy.doubleTapZone(0.91f, true))
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_RIGHT, PlayerGestureZonePolicy.doubleTapZone(1.00f, true))
    }

    @Test
    fun `越界坐标被钳制到屏内`() {
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_LEFT, PlayerGestureZonePolicy.doubleTapZone(-0.5f, true))
        assertEquals(DoubleTapZone.TOGGLE_OVERLAY_RIGHT, PlayerGestureZonePolicy.doubleTapZone(1.5f, true))
    }

    @Test
    fun `底部系统手势区起始的上滑被保留给系统`() {
        val height = 2000f
        assertTrue(PlayerGestureZonePolicy.isReservedSystemSwipe(1950f, height, bottomInsetPx = 96f))
        assertFalse(PlayerGestureZonePolicy.isReservedSystemSwipe(1900f, height, bottomInsetPx = 96f))
        assertFalse(PlayerGestureZonePolicy.isReservedSystemSwipe(1950f, height, bottomInsetPx = 0f))
    }

    @Test
    fun `安全边距可额外扩大保留区`() {
        assertTrue(
            PlayerGestureZonePolicy.isReservedSystemSwipe(
                startY = 1890f,
                heightPx = 2000f,
                bottomInsetPx = 96f,
                extraMarginPx = 24f,
            ),
        )
        assertFalse(
            PlayerGestureZonePolicy.isReservedSystemSwipe(
                startY = 1870f,
                heightPx = 2000f,
                bottomInsetPx = 96f,
                extraMarginPx = 24f,
            ),
        )
    }

    @Test
    fun `缩放倍率钳制在1到3倍`() {
        assertEquals(1f, PlayerGestureZonePolicy.clampZoom(0.3f), 0.001f)
        assertEquals(1.5f, PlayerGestureZonePolicy.clampZoom(1.5f), 0.001f)
        assertEquals(3f, PlayerGestureZonePolicy.clampZoom(8f), 0.001f)
    }
}
