package com.mediahub.feature.library

/**
 * 文件浏览器布局策略（条目 108/110）。
 *
 * 大图模式刻意比媒体库网格更宽：文件浏览以「看清单个文件」为主，
 * 单行只放 2–3 张，标题允许换行，避免长文件名被截断。
 */
fun fileBrowserColumns(availableWidthDp: Float, mode: LibraryViewMode): Int {
    if (mode == LibraryViewMode.LIST) return 1
    val minCellWidth = when (mode) {
        LibraryViewMode.LARGE -> 220f
        LibraryViewMode.STANDARD -> 140f
        LibraryViewMode.LIST -> 1f
    }
    return (availableWidthDp / minCellWidth).toInt().coerceIn(1, 4)
}

/** 文件浏览器标题行数（条目 110）：两类模式都允许换行，长文件名不再被截成一行。 */
fun fileBrowserTitleMaxLines(mode: LibraryViewMode): Int =
    if (mode == LibraryViewMode.LIST) 3 else 2
