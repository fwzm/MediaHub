package com.mediahub.feature.library

/**
 * L2（ui-visual-audit-2026-11-15 §6 P3）：媒体库海报墙列数策略。
 *
 * 固定 3 列在平板/折叠屏/横屏上会把海报拉得过大；这里按可用宽度与最小单元格宽度
 * 推导列数。内容区左右各 16dp 内边距（共 32dp）与 10dp 列间距参与计算，
 * 保证 360dp 手机仍为 3 列，宽屏逐步增加列数。
 */
internal const val LIBRARY_GRID_MIN_CELL_DP: Float = 100f
internal const val LIBRARY_GRID_SPACING_DP: Float = 10f
internal const val LIBRARY_GRID_HORIZONTAL_PADDING_DP: Float = 32f

internal fun libraryGridColumnCount(
    containerWidthDp: Float,
    minCellDp: Float = LIBRARY_GRID_MIN_CELL_DP,
    spacingDp: Float = LIBRARY_GRID_SPACING_DP,
    horizontalPaddingDp: Float = LIBRARY_GRID_HORIZONTAL_PADDING_DP,
): Int {
    if (!containerWidthDp.isFinite() || containerWidthDp <= 0f) return 1
    if (!minCellDp.isFinite() || minCellDp <= 0f) return 1
    if (!spacingDp.isFinite() || spacingDp < 0f) return 1
    val usableWidthDp = containerWidthDp - horizontalPaddingDp.coerceAtLeast(0f)
    if (usableWidthDp <= 0f) return 1
    val columns = ((usableWidthDp + spacingDp) / (minCellDp + spacingDp)).toInt()
    return columns.coerceAtLeast(1)
}
