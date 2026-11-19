package com.mediahub.feature.library

/**
 * 文件浏览网格的自适应列数策略（审计 #97）。
 *
 * 手机保持 3 列（既有视觉密度）；平板按可用宽度放宽，避免大屏仍挤成三列。
 * 宽度是 Compose 约束的 dp 值，不依赖具体设备型号。
 */
internal object LibraryGridPolicy {

    const val PHONE_COLUMNS: Int = 3
    const val TABLET_COLUMNS: Int = 5
    const val LARGE_TABLET_COLUMNS: Int = 6

    /** 依据可用宽度返回列数；600dp 起视为平板，900dp 起使用大平板密度。 */
    fun columnsForWidth(widthDp: Int): Int = when {
        widthDp >= 900 -> LARGE_TABLET_COLUMNS
        widthDp >= 600 -> TABLET_COLUMNS
        else -> PHONE_COLUMNS
    }
}
