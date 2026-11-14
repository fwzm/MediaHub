package com.mediahub.core.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 相对亮度与对比度（条目 103）。
 *
 * 放在 core:ui 是因为它要同时约束「组件实际用的颜色」和「JVM 测试里的期望值」，
 * 不依赖 Compose 运行时，因此可以在纯 JVM 单测里跑。
 */
fun relativeLuminance(argb: Int): Double {
    val red = linearChannel((argb shr 16) and 0xFF)
    val green = linearChannel((argb shr 8) and 0xFF)
    val blue = linearChannel(argb and 0xFF)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/** 对比度 (L1 + 0.05) / (L2 + 0.05)，与前景/背景顺序无关。 */
fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
    val first = relativeLuminance(fgArgb)
    val second = relativeLuminance(bgArgb)
    return (max(first, second) + 0.05) / (min(first, second) + 0.05)
}

private fun linearChannel(value: Int): Double {
    val channel = value / 255.0
    return if (channel <= 0.03928) {
        channel / 12.92
    } else {
        ((channel + 0.055) / 1.055).pow(2.4)
    }
}

/**
 * 菜单副标题在黑色背景下的可读口径。
 *
 * 旧值 [LEGACY_COLOR_ARGB]（#AFB9CE）+ bodySmall(12sp) 在纯黑底上对比度约 9.8:1，
 * 数值够但字重与字号偏弱；这里提到 13sp / Medium 并再提亮一档，达到 AAA（7:1 以上）。
 */
object MenuSubtitleSpec {
    const val FONT_SIZE_SP = 13
    const val FONT_WEIGHT = 500
    const val MIN_CONTRAST = 7.0

    /** 应用最底层背景（Theme.kt 的 background / surface）。 */
    val BACKGROUND_ARGB: Int = 0xFF0A0D14.toInt()

    /** 卡片/分区底色（Theme.kt 的 surfaceContainer）。 */
    val SURFACE_ARGB: Int = 0xFF161C28.toInt()

    /** 新副标题色：比 onSurfaceVariant 更亮，纯黑背景上约 11.9:1。 */
    val COLOR_ARGB: Int = 0xFFC3CBDC.toInt()

    /** 旧颜色，仅用于测试证明提亮确实发生了。 */
    val LEGACY_COLOR_ARGB: Int = 0xFFAFB9CE.toInt()
}
