package com.mediahub.feature.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 视觉审计 D4：详情页 hero 的标题列原本没有独立 scrim，白字直接压在 backdrop 上，
 * 亮图时对比度会掉到 3:1 以下。这里给标题列一层局部竖直渐变 scrim：
 * 顶部最重（白字所在处），向下淡出到透明，避免整块变灰。
 */
internal const val DETAIL_TITLE_SCRIM_TOP_ALPHA = 0.92f

internal const val DETAIL_TITLE_SCRIM_MID_OFFSET = 0.55f

internal const val DETAIL_TITLE_SCRIM_MID_ALPHA = 0.48f

/**
 * 标题列 scrim 的渐变 stop 表。所有 stop 都用主题背景色着色，只有 alpha 变化，
 * 这样深色/浅色主题下都不会引入第三种色相。
 */
internal fun detailTitleScrimStops(background: Color): List<Pair<Float, Color>> = listOf(
    0f to background.copy(alpha = DETAIL_TITLE_SCRIM_TOP_ALPHA),
    DETAIL_TITLE_SCRIM_MID_OFFSET to background.copy(alpha = DETAIL_TITLE_SCRIM_MID_ALPHA),
    1f to background.copy(alpha = 0f),
)

/** 供标题列直接使用的竖直渐变画刷。 */
@Composable
internal fun detailTitleScrimBrush(): Brush =
    Brush.verticalGradient(colorStops = detailTitleScrimStops(MaterialTheme.colorScheme.background).toTypedArray())
