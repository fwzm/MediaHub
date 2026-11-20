package com.mediahub.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediahub.core.ui.MediaHubSectionHeader

/**
 * 视觉审计 D11：详情页的「季」用 FilterChip + 8dp 间距，但剧集行与简介又回归另一套排版。
 *
 * 这里把详情页所有区块收进同一套样式：区块之间 16dp、区块标题统一走 core/ui 的
 * [MediaHubSectionHeader]（titleLarge），季 chip 行间距 8dp。新增区块一律复用这两个 token，
 * 不要再写一次性字面量。
 */
internal const val DETAIL_SECTION_SPACING_DP = 16

/** 季选择 chip 行（LazyRow）的水平间距。 */
internal const val DETAIL_SEASON_CHIP_SPACING_DP = 8

internal data class DetailSectionSpec(
    val title: String,
    val topSpacingDp: Int,
)

internal fun detailSectionSpec(title: String): DetailSectionSpec = DetailSectionSpec(
    title = title,
    topSpacingDp = DETAIL_SECTION_SPACING_DP,
)

/** 详情页统一区块标题：与「剧情简介」同款 [MediaHubSectionHeader] 样式。 */
@Composable
internal fun DetailSectionHeader(title: String, modifier: Modifier = Modifier) {
    MediaHubSectionHeader(title = title, modifier = modifier)
}
