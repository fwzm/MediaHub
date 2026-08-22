package com.mediahub.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 播放器系统 UI 控制器（Phase Player UX：自动横屏 + 沉浸式系统栏）。
 *
 * 进入播放器保存原方向并应用播放态；退出恢复原方向与系统栏。
 * 由 DisposableEffect 生命周期兜底，不依赖"正常返回按钮"单一路径。
 *
 * 为后续 Overlay 预留 [showSystemBarsTemporarily]（当前 Android 系统栏默认隐藏，
 * 边缘滑动经 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 临时唤出，不常驻）。
 */
class PlayerSystemUiController(private val activity: Activity) {

    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var originalConfigOrientation = Configuration.ORIENTATION_UNDEFINED
    private var entered = false

    fun enterPlayback(autoLandscape: Boolean, immersiveBars: Boolean) {
        if (entered) return
        entered = true
        // 保存进入播放器前的方向（用户可能原本就是横屏/反向横屏，退出不能强制竖屏）
        originalOrientation = activity.requestedOrientation
        originalConfigOrientation = activity.resources.configuration.orientation
        Log.d("MediaHub/SYSUI", "enterPlayback originalOrientation=" + originalOrientation + " config=" + originalConfigOrientation)
        if (autoLandscape) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (immersiveBars) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            controller().apply {
                hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** 临时唤出系统栏（后续 Overlay / 特殊交互预留；当前由边缘滑动 transient bars 覆盖）。 */
    fun showSystemBarsTemporarily() {
        if (!entered) return
        controller().show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
    }

    fun exitPlayback() {
        if (!entered) return
        entered = false
        controller().show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        val restoreOrientation = resolveRestoreOrientation()
        Log.d("MediaHub/SYSUI", "exitPlayback restoring requestedOrientation=" + restoreOrientation)
        activity.requestedOrientation = restoreOrientation
    }

    /**
     * 计算退出时恢复的方向。MIUI 对动态方向（UNSPECIFIED/SENSOR/USER）恢复不敏感，
     * 显式指定方向（SENSOR_LANDSCAPE/PORTRAIT）才可靠；故把「原本竖屏/横屏」映射成显式值。
     */
    private fun resolveRestoreOrientation(): Int = when (originalOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        else -> if (originalConfigOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun controller(): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
}

/** 从 Context 逐层解包出宿主 Activity（Compose 局部 Context 可能是 ContextWrapper）。 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
