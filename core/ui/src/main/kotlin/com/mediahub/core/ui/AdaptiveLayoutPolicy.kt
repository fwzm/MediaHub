package com.mediahub.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式布局断点策略（#26）：
 * 竖版海报网格与继续观看卡片在不同窗口宽度下使用不同尺寸，避免手机沿用固定 3 列/276dp
 * 在平板或窄屏上出现过密或过疏的布局。纯 Kotlin，可在 JVM 单测中直接验证。
 */
object AdaptiveLayoutPolicy {

    /** 媒体库竖版海报网格列数：手机默认 3 列，平板逐级增加，窄屏降为 2 列。 */
    fun libraryColumnCount(screenWidthDp: Int): Int = when {
        screenWidthDp >= 1000 -> 6
        screenWidthDp >= 840 -> 5
        screenWidthDp >= 600 -> 4
        screenWidthDp >= 360 -> 3
        else -> 2
    }

    /** 继续观看卡片宽度：手机保持 276dp，平板加宽，窄屏收窄。 */
    fun continueWatchingCardWidth(screenWidthDp: Int): Dp = when {
        screenWidthDp >= 840 -> 360.dp
        screenWidthDp >= 600 -> 320.dp
        screenWidthDp >= 360 -> 276.dp
        else -> 232.dp
    }
}
