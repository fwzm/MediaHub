package com.mediahub.provider.api

/**
 * 服务器分页窗口（A2-4 第 5 项）：Emby / Jellyfin 各 Provider 的 hasMore/nextOffset
 * 统一一份数学，杜绝四处各自实现再次漂移。
 *
 * 空页守卫：本页返回 0 条（returnedCount <= 0）时必须终止分页。
 * 此前的 `(offset + 0) < total` 组合在 total 更大时产生 nextOffset == offset，
 * loadMore 用同一 offset 重复请求 → 无限循环追加空页。
 * 只收紧空页终止条件，不改变非空页的 offset/total 数学与排序/筛选语义。
 */
data class PageWindow(
    val hasMore: Boolean,
    val nextOffset: Int?,
)

fun pageWindow(offset: Int, returnedCount: Int, totalCount: Int): PageWindow {
    if (returnedCount <= 0) return PageWindow(hasMore = false, nextOffset = null)
    val next = offset + returnedCount
    val hasMore = next < totalCount
    return PageWindow(
        hasMore = hasMore,
        nextOffset = if (hasMore) next else null,
    )
}
