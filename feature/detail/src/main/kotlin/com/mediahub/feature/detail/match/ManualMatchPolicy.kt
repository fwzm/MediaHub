package com.mediahub.feature.detail.match

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType

/**
 * 手动匹配页布局策略（条目 129 / 133）。
 *
 * 129「抽搐」的根因是列表项高度随内容/状态浮动：把行高、标题行数、请求条数
 * 全部固化成常量，实现层只照抄，不在列表里用自适应高度动画。
 */

/** 输入去抖：连续击键只发最后一次请求。 */
const val MATCH_QUERY_DEBOUNCE_MS = 350L

/** 候选行固定高度：内容多少都不改变行高，滚动条与列表不会抖。 */
const val MATCH_RESULT_ROW_HEIGHT_DP = 76

/** 候选标题最多两行，超出省略。 */
const val MATCH_RESULT_TITLE_MAX_LINES = 2

/** 列表底部留白，保证最后一行能滚出软键盘与手势区。 */
const val MATCH_LIST_BOTTOM_PADDING_DP = 96

/** 顶部「当前条目 + 搜索框」占用的固定高度。 */
const val MATCH_LIST_RESERVED_HEADER_DP = 168f

private const val MIN_REQUEST = 6
private const val MAX_REQUEST = 20

/**
 * 一屏候选需要的请求条数：一屏行数 × 2（留一屏滚动余量），
 * 下限 6 条避免空列表抖动，上限 20 条避免一次拉爆。
 */
fun matchRequestLimit(availableHeightDp: Float): Int {
    val usable = availableHeightDp - MATCH_LIST_RESERVED_HEADER_DP
    if (usable <= 0f) return MIN_REQUEST
    val rows = (usable / MATCH_RESULT_ROW_HEIGHT_DP).toInt().coerceAtLeast(1)
    return (rows * 2).coerceIn(MIN_REQUEST, MAX_REQUEST)
}

/** 候选副标题：`年份 · 类型`，无年份时只显示类型。 */
fun matchCandidateLine(item: MediaItem): String {
    val typeLabel = when (item.type) {
        MediaType.MOVIE -> "电影"
        MediaType.SERIES -> "剧集"
        MediaType.SEASON -> "季"
        MediaType.EPISODE -> "单集"
        MediaType.VIDEO -> "视频"
        MediaType.AUDIO -> "音频"
        MediaType.FOLDER -> "文件夹"
        MediaType.LIVE_TV -> "直播"
        MediaType.OTHER -> "其他"
    }
    return item.year?.let { "$it · $typeLabel" } ?: typeLabel
}

/** 空白输入只提示、不发请求。 */
fun matchQueryHint(query: String?): String? =
    if (query.isNullOrBlank()) "输入片名开始匹配" else null
