package com.mediahub.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mediahub.core.ui.effects.contrastRatio

/**
 * #28 菜单副标题在深色/黑色容器上的可读性：与播放器无关的通用色阶。
 * 保留候选色，只有在对比度不足时才向黑白端点混合，避免菜单文字“看得到但读不清”。
 */
object MenuSubtitleColor {
    /** WCAG AA 正文对比度阈值。 */
    const val MIN_CONTRAST = 4.5f

    fun resolve(candidate: Color, background: Color): Color {
        val bg = background.opaque()
        val opaqueCandidate = candidate.opaque()
        if (contrastRatio(opaqueCandidate, bg) >= MIN_CONTRAST) return opaqueCandidate
        val target = bestContrastingText(bg)
        for (step in 1..20) {
            val adjusted = blend(opaqueCandidate, target, step / 20f)
            if (contrastRatio(adjusted, bg) >= MIN_CONTRAST) return adjusted
        }
        return target
    }

    private fun bestContrastingText(background: Color): Color =
        if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
            Color.White
        } else {
            Color.Black
        }

    private fun blend(from: Color, toward: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            red = from.red + (toward.red - from.red) * t,
            green = from.green + (toward.green - from.green) * t,
            blue = from.blue + (toward.blue - from.blue) * t,
            alpha = 1f,
        )
    }

    private fun Color.opaque(): Color = copy(alpha = 1f)
}

/** 菜单/卡片副标题色：按实际容器色保证对比度。 */
@Composable
fun menuSubtitleColor(containerColor: Color): Color =
    MenuSubtitleColor.resolve(MaterialTheme.colorScheme.onSurfaceVariant, containerColor)
