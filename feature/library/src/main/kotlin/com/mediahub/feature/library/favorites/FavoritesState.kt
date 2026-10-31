package com.mediahub.feature.library.favorites

import com.mediahub.model.MediaItem

/**
 * 「我的收藏」列表状态（task-25 / A2）。
 *
 * 数据来源：当前服务器的 `MediaFavoriteProvider.listFavorites`（服务端
 * Filters=IsFavorite + 客户端 isFavorite 兜底），本机没有收藏表。
 */
sealed interface FavoritesUiState {
    data object Loading : FavoritesUiState

    data class Empty(val message: String = "还没有收藏") : FavoritesUiState

    data class Content(
        val items: List<MediaItem>,
        /** 刷新失败但旧列表仍可用时只挂提示，不清空内容。 */
        val refreshError: String? = null,
    ) : FavoritesUiState

    data class Error(val message: String) : FavoritesUiState
}

/** 纯状态迁移（不依赖 Compose / Android），单元测试直接覆盖。 */
object FavoritesState {

    fun loading(): FavoritesUiState = FavoritesUiState.Loading

    fun loaded(items: List<MediaItem>): FavoritesUiState =
        if (items.isEmpty()) FavoritesUiState.Empty() else FavoritesUiState.Content(items)

    /**
     * 失败语义：已有内容时保留列表 + 可读错误；没有内容时才是全屏错误态。
     * 绝不把"加载失败"渲染成"没有收藏"。
     */
    fun failed(previous: FavoritesUiState, message: String): FavoritesUiState = when (previous) {
        is FavoritesUiState.Content -> previous.copy(refreshError = message)
        else -> FavoritesUiState.Error(message)
    }

    /** 数据源没有收藏能力：明确说明，而不是显示空态。 */
    fun unsupported(sourceLabel: String = "该数据源"): FavoritesUiState =
        FavoritesUiState.Error(sourceLabel + " 暂不支持收藏")
}
