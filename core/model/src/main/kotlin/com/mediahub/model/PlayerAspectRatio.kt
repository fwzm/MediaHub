package com.mediahub.model

/**
 * 播放画面比例（#9）：跨影片记忆在 UserPreferences，播放页可即时切换。
 *
 * FIT  = 按视频原始宽高比适应屏幕（未知视频尺寸时铺满）；
 * FILL = 拉伸铺满整个播放区域。
 */
enum class PlayerAspectRatio {
    FIT,
    FILL,
    RATIO_16_9,
    RATIO_4_3,
}
