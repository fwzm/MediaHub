package com.mediahub.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L2（ui-visual-audit-2026-11-15 §6 P3）：海报墙列数必须随可用宽度变化，
 * 而不是固定 3 列。纯函数承载策略，便于在 JVM 上验证手机/平板/横屏边界。
 */
class LibraryGridPolicyTest {

    @Test
    fun phonePortraitKeepsThreeColumns() {
        // 360dp 手机、左右各 16dp 内容内边距、10dp 间距：仍应是熟悉的 3 列
        assertEquals(3, libraryGridColumnCount(containerWidthDp = 360f))
    }

    @Test
    fun tabletLandscapeUsesSevenColumns() {
        // 800dp 平板/横屏：旧实现固定 3 列会把海报拉得过大，新策略应铺满 7 列
        assertEquals(7, libraryGridColumnCount(containerWidthDp = 800f))
    }

    @Test
    fun widePhonesAndSmallTabletsScaleGradually() {
        assertEquals(4, libraryGridColumnCount(containerWidthDp = 480f))
        assertEquals(5, libraryGridColumnCount(containerWidthDp = 600f))
    }

    @Test
    fun narrowWindowNeverDropsBelowOneColumn() {
        assertEquals(1, libraryGridColumnCount(containerWidthDp = 128f))
        assertEquals(1, libraryGridColumnCount(containerWidthDp = 40f))
    }

    @Test
    fun invalidOrMissingWidthFallsBackToOneColumn() {
        assertEquals(1, libraryGridColumnCount(containerWidthDp = 0f))
        assertEquals(1, libraryGridColumnCount(containerWidthDp = -100f))
        assertEquals(1, libraryGridColumnCount(containerWidthDp = Float.NaN))
        assertEquals(1, libraryGridColumnCount(containerWidthDp = Float.POSITIVE_INFINITY))
    }

    @Test
    fun customMinimumCellAndSpacingAreRespected() {
        // 800dp - 32dp padding = 768dp；minCell 200 + spacing 10 => 每列 210dp，可放 3 列
        assertEquals(3, libraryGridColumnCount(containerWidthDp = 800f, minCellDp = 200f, spacingDp = 10f))
        // spacing 为 0 时仍按 minCell 计算：300dp / 100dp = 3 列
        assertEquals(3, libraryGridColumnCount(containerWidthDp = 332f, minCellDp = 100f, spacingDp = 0f))
    }
}
