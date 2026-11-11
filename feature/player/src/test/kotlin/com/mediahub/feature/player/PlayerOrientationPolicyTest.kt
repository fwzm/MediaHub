package com.mediahub.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 回归：**非播放页面必须恒为竖屏**。
 *
 * 真实缺陷（用户可见）：MainActivity 未声明 screenOrientation（默认 UNSPECIFIED），
 * 而 exitPlayback 的兜底分支用「进入播放器时的物理配置方向」作为恢复目标 ——
 * 用户在横持状态下点播放，退出后恢复成 SENSOR_LANDSCAPE，**首页因此停留在横屏**。
 *
 * 本测试用 Robolectric 的 land 限定符复现该场景：进入前 requestedOrientation=UNSPECIFIED
 * 且资源配置为横屏 —— 旧实现恢复成 SENSOR_LANDSCAPE（RED），修复后必须恢复 PORTRAIT。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "land")
class PlayerOrientationPolicyTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `exiting playback returns the activity to portrait even if playback started while landscape`() {
        val activity = activity()
        // 进入播放器前：MainActivity 未声明方向 => UNSPECIFIED（跟随传感器）
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        val controller = PlayerSystemUiController(activity)
        controller.enterPlayback(autoLandscape = true, immersiveBars = false)
        assertEquals(
            "播放器应进入横屏",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            activity.requestedOrientation,
        )

        controller.exitPlayback()

        assertEquals(
            "退出播放器后必须回到竖屏：非播放页面不得跟随物理传感器",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }

    @Test
    fun `exiting playback from a portrait entry also ends in portrait`() {
        val activity = activity()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val controller = PlayerSystemUiController(activity)
        controller.enterPlayback(autoLandscape = true, immersiveBars = false)
        controller.exitPlayback()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    fun `auto landscape disabled keeps portrait on entry and exit`() {
        val activity = activity()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = PlayerSystemUiController(activity)
        controller.enterPlayback(autoLandscape = false, immersiveBars = false)
        assertEquals(
            "关闭自动横屏时播放器也不得改方向",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation,
        )
        controller.exitPlayback()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }
}
