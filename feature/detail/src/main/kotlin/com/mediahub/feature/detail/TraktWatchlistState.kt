package com.mediahub.feature.detail

/**
 * 条目 146：详情页 Trakt 追更状态。
 *
 * null（不渲染入口）表示未配置 Trakt 凭据或当前条目不是剧集；
 * [error] 为可直接展示的中文失败原因（限流时含等待秒数）。
 */
data class TraktWatchlistUiState(
    val isTracked: Boolean,
    val traktId: Int? = null,
    val isUpdating: Boolean = false,
    val error: String? = null,
)
