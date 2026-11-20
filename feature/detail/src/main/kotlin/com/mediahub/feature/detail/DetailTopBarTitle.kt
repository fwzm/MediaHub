package com.mediahub.feature.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * 详情页顶栏标题（视觉审计 §2.3 D1）。
 *
 * 审计原文：标题样式是全 App 的第 3 种写法——裸 `Text(displayTitle)` 走默认 titleLarge，
 * 既没有 accent，也不会随滚动变色。这里把三件事收敛成可单测的纯函数：
 * 标题取值（内容标题优先、路由参数兜底）、滚动折叠时的强调度、以及固定单行省略。
 */
internal const val DETAIL_TOPBAR_TITLE_MAX_LINES = 1

/** 展开态（未滚动）的标题强调度：弱化，避免和 hero 里的白色大标题抢注意力。 */
internal const val DETAIL_TOPBAR_EXPANDED_ALPHA = 0.72f

/** 折叠态（顶栏压到最窄）的标题强调度：完全不透明，此时标题是唯一的身份线索。 */
internal const val DETAIL_TOPBAR_COLLAPSED_ALPHA = 1.0f

/**
 * 顶栏标题文本：内容加载完成后用条目标题，未加载/空白时回退到路由带来的标题。
 * 两边都可能是空白，此时返回空串（调用方仍会渲染顶栏，只是没有文字）。
 */
internal fun detailTopBarTitle(contentTitle: String?, routeTitle: String): String {
    val fromContent = contentTitle?.trim().orEmpty()
    if (fromContent.isNotEmpty()) return fromContent
    return routeTitle.trim()
}

/**
 * 标题强调度随顶栏折叠线性提升：0f（展开）→ [DETAIL_TOPBAR_EXPANDED_ALPHA]，
 * 1f（折叠）→ [DETAIL_TOPBAR_COLLAPSED_ALPHA]。越界值按 0..1 夹紧。
 */
internal fun detailTopBarTitleAlpha(collapsedFraction: Float): Float {
    val fraction = collapsedFraction.coerceIn(0f, 1f)
    return DETAIL_TOPBAR_EXPANDED_ALPHA +
        (DETAIL_TOPBAR_COLLAPSED_ALPHA - DETAIL_TOPBAR_EXPANDED_ALPHA) * fraction
}

/** 顶栏标题：统一字号阶（titleMedium）、随滚动变色的强调度、固定单行省略。 */
@Composable
internal fun DetailTopBarTitle(
    text: String,
    collapsedFraction: Float,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = detailTopBarTitleAlpha(collapsedFraction)),
        maxLines = DETAIL_TOPBAR_TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}
