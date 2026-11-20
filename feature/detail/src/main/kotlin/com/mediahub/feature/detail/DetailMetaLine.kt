package com.mediahub.feature.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 详情页 hero 元信息行（审计 D5）：单一分隔符 token + 统一字号/行高，
 * 不再手工拼 "  ·  "（双空格中点）导致间距不可控。
 */
internal const val DETAIL_META_SEPARATOR = " · "

/** 把年份 / 时长 / 评分 / 容器拼成一行元信息；无可用字段返回 null（调用方不占位）。 */
internal fun detailMetaText(
    year: Int?,
    runtimeMs: Long?,
    communityRating: Double?,
    container: String?,
): String? {
    val parts = buildList {
        year?.let { add(it.toString()) }
        runtimeMs?.let { ms ->
            val minutes = ms / 60_000
            if (minutes > 0) add("${minutes}分钟")
        }
        communityRating?.let { add("★ %.1f".format(it)) }
        container?.takeIf(String::isNotBlank)?.uppercase()?.let { add(it) }
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(DETAIL_META_SEPARATOR)
}

@Composable
internal fun DetailMetaLine(
    year: Int?,
    runtimeMs: Long?,
    communityRating: Double?,
    container: String?,
    modifier: Modifier = Modifier,
) {
    val text = detailMetaText(year, runtimeMs, communityRating, container) ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.86f),
        modifier = modifier,
        maxLines = 2,
    )
}
