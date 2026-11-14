package com.mediahub.feature.search

/**
 * 搜索历史展示策略（条目 119）。
 *
 * 用户约束：与另一路搜索体验改动有交集时**只改布局常量，不碰存储**。
 * 因此这里只定义「固定两行」的排布口径，历史数据的读写由搜索模块自行接线。
 */
const val SEARCH_HISTORY_ROWS = 2

/** 单行可容纳的历史条目数（按最小 chip 宽度 96dp 估算，2–6 列）。 */
fun searchHistoryColumns(availableWidthDp: Float): Int =
    (availableWidthDp / 96f).toInt().coerceIn(2, 6)

/** 固定两行可展示的历史条目总数。 */
fun visibleSearchHistoryCount(availableWidthDp: Float): Int =
    searchHistoryColumns(availableWidthDp) * SEARCH_HISTORY_ROWS
