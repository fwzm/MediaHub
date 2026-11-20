package com.mediahub.feature.detail

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape

/** 审计 D8：导演行垂直内边距原为 2dp，视觉上贴住上一块内容，统一为 8dp。 */
internal const val DETAIL_DIRECTOR_ROW_VERTICAL_PADDING_DP = 8

/** 审计 D9：演员头像必须是正圆，而不是 50% 的圆角矩形。 */
internal fun detailAvatarShape(): Shape = CircleShape

/** 审计 D12：简介折叠时为 4 行，展开后不限制行数。 */
internal fun detailSynopsisMaxLines(expanded: Boolean): Int = if (expanded) Int.MAX_VALUE else 4
