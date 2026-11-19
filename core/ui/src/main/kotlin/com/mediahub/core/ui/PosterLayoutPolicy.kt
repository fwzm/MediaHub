package com.mediahub.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 竖版海报的响应式布局策略。
 *
 * 屏幕宽度按 Android 常见窗口档位分档：
 * - compact（< 360dp）：96dp 宽，标准 2:3；
 * - phone（360–599dp）：104dp 宽，标准 2:3；
 * - medium（600–839dp）：120dp 宽，0.70 宽高比；
 * - expanded（>= 840dp）：136dp 宽，0.72 宽高比。
 *
 * 宽高比同时按档位略放宽，使大屏海报高度不会只随固定 2:3 比例线性变高；
 * 详情海报宽度由 [detailPosterWidth] 决定，高度由宽高比推导（宽度 / aspectRatio）。
 */
object PosterLayoutPolicy {
    fun detailPosterWidth(screenWidthDp: Int): Dp = when {
        screenWidthDp >= 840 -> 136.dp
        screenWidthDp >= 600 -> 120.dp
        screenWidthDp >= 360 -> 104.dp
        else -> 96.dp
    }

    fun posterAspectRatio(screenWidthDp: Int): Float = when {
        screenWidthDp >= 840 -> 0.72f
        screenWidthDp >= 600 -> 0.70f
        else -> 2f / 3f
    }
}
