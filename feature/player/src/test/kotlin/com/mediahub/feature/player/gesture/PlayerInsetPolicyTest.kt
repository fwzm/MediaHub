package com.mediahub.feature.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放器控制层安全区计算（缺陷 32）。
 *
 * 个别设备（刘海/挖孔屏）在沉浸式隐藏状态栏后，`statusBars` inset 变为 0，
 * 但 `displayCutout` 仍然存在，导致顶部按钮被状态栏/挖孔遮挡。
 * 规则：顶部取 statusBars 与 displayCutout 的最大值；左右取挖孔；底部取导航栏与系统手势的最大值。
 */
class PlayerInsetPolicyTest {

    @Test
    fun `普通设备取状态栏和导航栏高度`() {
        val insets = PlayerInsetPolicy.resolve(
            statusBarTopDp = 24f,
            cutoutTopDp = 0f,
            cutoutLeftDp = 0f,
            cutoutRightDp = 0f,
            navigationBarBottomDp = 48f,
            systemGestureBottomDp = 0f,
        )
        assertEquals(0f, insets.leftDp, 0.001f)
        assertEquals(24f, insets.topDp, 0.001f)
        assertEquals(0f, insets.rightDp, 0.001f)
        assertEquals(48f, insets.bottomDp, 0.001f)
    }

    @Test
    fun `沉浸式隐藏状态栏后仍为刘海保留顶部安全区`() {
        val insets = PlayerInsetPolicy.resolve(
            statusBarTopDp = 0f,
            cutoutTopDp = 44f,
            cutoutLeftDp = 0f,
            cutoutRightDp = 0f,
            navigationBarBottomDp = 0f,
            systemGestureBottomDp = 24f,
        )
        assertEquals(44f, insets.topDp, 0.001f)
        assertEquals(24f, insets.bottomDp, 0.001f)
    }

    @Test
    fun `横屏刘海在左右两侧时控制层避让`() {
        val insets = PlayerInsetPolicy.resolve(
            statusBarTopDp = 0f,
            cutoutTopDp = 0f,
            cutoutLeftDp = 32f,
            cutoutRightDp = 32f,
            navigationBarBottomDp = 0f,
            systemGestureBottomDp = 0f,
        )
        assertEquals(32f, insets.leftDp, 0.001f)
        assertEquals(32f, insets.rightDp, 0.001f)
    }

    @Test
    fun `底部同时存在导航栏与系统手势时取较大值`() {
        val insets = PlayerInsetPolicy.resolve(
            statusBarTopDp = 0f,
            cutoutTopDp = 0f,
            cutoutLeftDp = 0f,
            cutoutRightDp = 0f,
            navigationBarBottomDp = 48f,
            systemGestureBottomDp = 64f,
        )
        assertEquals(64f, insets.bottomDp, 0.001f)
    }

    @Test
    fun `负值得不到负数内边距`() {
        val insets = PlayerInsetPolicy.resolve(
            statusBarTopDp = -8f,
            cutoutTopDp = 0f,
            cutoutLeftDp = -1f,
            cutoutRightDp = -1f,
            navigationBarBottomDp = -1f,
            systemGestureBottomDp = -1f,
        )
        assertEquals(0f, insets.leftDp, 0.001f)
        assertEquals(0f, insets.topDp, 0.001f)
        assertEquals(0f, insets.rightDp, 0.001f)
        assertEquals(0f, insets.bottomDp, 0.001f)
    }
}
