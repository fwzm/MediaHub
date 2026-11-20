package com.mediahub.feature.player

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放页常亮（#104）：进入播放器必须持有 FLAG_KEEP_SCREEN_ON，退出必须恢复进入前的状态。
 * 只验证窗口标志位契约；真机长时间播放仍需真机复核（见 AGENTS.md 证据分级）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSystemUiControllerTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun Activity.keepScreenOn(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun enteringPlaybackKeepsScreenOn() {
        val activity = activity()
        val controller = PlayerSystemUiController(activity)

        controller.enterPlayback(autoLandscape = false, immersiveBars = false)

        assertTrue("播放期间窗口必须持有 FLAG_KEEP_SCREEN_ON", activity.keepScreenOn())
    }

    @Test
    fun exitingPlaybackClearsTheFlagWhenItWasOffBefore() {
        val activity = activity()
        val controller = PlayerSystemUiController(activity)
        controller.enterPlayback(autoLandscape = false, immersiveBars = false)

        controller.exitPlayback()

        assertFalse("退出播放器必须清除自己加上的常亮标志", activity.keepScreenOn())
    }

    @Test
    fun exitingPlaybackPreservesAPreExistingFlag() {
        val activity = activity()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = PlayerSystemUiController(activity)

        controller.enterPlayback(autoLandscape = false, immersiveBars = false)
        controller.exitPlayback()

        assertTrue("进入前已存在的常亮标志不得被退出流程清除", activity.keepScreenOn())
    }

    @Test
    fun repeatedEnterDoesNotForgetTheOriginalFlagState() {
        val activity = activity()
        val controller = PlayerSystemUiController(activity)

        controller.enterPlayback(autoLandscape = false, immersiveBars = false)
        controller.enterPlayback(autoLandscape = true, immersiveBars = true)
        controller.exitPlayback()

        assertFalse("重复进入不得把常亮状态记成开启", activity.keepScreenOn())
    }

    @Test
    fun exitRestoresPortraitOrientation() {
        val activity = activity()
        val controller = PlayerSystemUiController(activity)
        controller.enterPlayback(autoLandscape = true, immersiveBars = false)

        controller.exitPlayback()

        assertEquals(
            "退出播放器后必须回到竖屏",
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }
}
