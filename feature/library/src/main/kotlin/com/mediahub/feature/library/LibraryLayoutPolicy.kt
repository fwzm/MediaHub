package com.mediahub.feature.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType

/** 媒体库/文件浏览器视图模式（条目 108）。 */
enum class LibraryViewMode { LARGE, STANDARD, LIST }

/** 资源库 Logo 尺寸（条目 77）。 */
val LibraryLogoSize: Dp = 44.dp

/**
 * 自适应列数（条目 17/26/108）：按可用宽度与视图模式推导，不依赖具体机型。
 * 大图模式单格更宽，列表模式恒 1 列；上限 6 列避免超宽屏出现细条。
 */
fun libraryGridColumns(availableWidthDp: Float, mode: LibraryViewMode): Int {
    if (mode == LibraryViewMode.LIST) return 1
    val minCellWidth = when (mode) {
        LibraryViewMode.LARGE -> 156f
        LibraryViewMode.STANDARD -> 104f
        LibraryViewMode.LIST -> 1f
    }
    return (availableWidthDp / minCellWidth).toInt().coerceIn(1, 6)
}

/**
 * 海报/缩略图纵横比（条目 13/14/17）：
 * - 单集/视频本身就是横版缩略图；
 * - 电影/剧集有竖版海报用 2:3；
 * - 只有横版背景图，或横屏窗口下无竖版海报时用 16:9。
 */
fun artworkAspect(item: MediaItem, landscapeWindow: Boolean): Float = when {
    item.type == MediaType.EPISODE || item.type == MediaType.VIDEO -> 16f / 9f
    item.posterUrl != null -> 2f / 3f
    item.backdropUrl != null -> 16f / 9f
    landscapeWindow -> 16f / 9f
    else -> 2f / 3f
}

/** 标题换行行数（条目 110）：列表模式信息密度低，允许 3 行；网格 2 行。 */
fun titleMaxLines(mode: LibraryViewMode): Int =
    if (mode == LibraryViewMode.LIST) 3 else 2

/**
 * 纯函数重排（条目 4）：越界或原位返回原列表，调用方无需先做边界判断。
 */
fun reorderIds(current: List<String>, from: Int, to: Int): List<String> {
    if (from == to || from !in current.indices || to !in current.indices) return current
    val mutable = current.toMutableList()
    mutable.add(to, mutable.removeAt(from))
    return mutable
}
