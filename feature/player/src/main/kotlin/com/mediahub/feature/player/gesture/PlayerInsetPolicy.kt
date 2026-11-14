package com.mediahub.feature.player.gesture

/**
 * 播放器控制层需要避让的安全区（缺陷 32，单位 dp）。
 *
 * 个别设备（刘海/挖孔屏）在沉浸式隐藏状态栏后 `statusBars` 为 0，
 * 但 `displayCutout` 仍然存在；只按状态栏做 padding 会让顶部控件被遮挡。
 */
data class PlayerChromeInsets(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float,
)

object PlayerInsetPolicy {

    /**
     * 顶部 = max(状态栏, 挖孔)，左右 = 挖孔，底部 = max(导航栏, 系统手势)。
     * 所有负值归零，避免异常设备上报负 inset 造成控件被裁切。
     */
    fun resolve(
        statusBarTopDp: Float,
        cutoutTopDp: Float,
        cutoutLeftDp: Float,
        cutoutRightDp: Float,
        navigationBarBottomDp: Float,
        systemGestureBottomDp: Float,
    ): PlayerChromeInsets = PlayerChromeInsets(
        leftDp = cutoutLeftDp.coerceAtLeast(0f),
        topDp = maxOf(statusBarTopDp, cutoutTopDp).coerceAtLeast(0f),
        rightDp = cutoutRightDp.coerceAtLeast(0f),
        bottomDp = maxOf(navigationBarBottomDp, systemGestureBottomDp).coerceAtLeast(0f),
    )
}
