package com.mediahub.core.ui.effects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Contrast-safe colors consumed only by playback controls and their local Material theme. */
@Immutable
data class PlayerVisualPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onAccent: Color,
) {
    companion object {
        fun from(source: VisualPalette): PlayerVisualPalette {
            val background = Color(source.background).opaque()
            val rawPrimary = Color(source.primary).opaque()
            val rawSecondary = Color(source.secondary).opaque()
            val rawAccent = Color(source.accent).opaque()

            // Player chrome stays dark enough for video readability, even when artwork is pale.
            val surface = blend(
                from = background,
                toward = Color.Black,
                amount = if (relativeLuminance(background) > 0.35f) 0.72f else 0.32f,
            )
            val surfaceVariant = blend(surface, rawSecondary, 0.18f)
            val onSurface = bestContrastingText(surface)
            val onSurfaceVariant = ensureContrast(
                candidate = blend(onSurface, surfaceVariant, 0.12f),
                background = surfaceVariant,
                minimumRatio = TEXT_CONTRAST,
            )
            val primary = ensureContrast(rawPrimary, surface, CONTROL_CONTRAST)
            val secondary = ensureContrast(rawSecondary, surface, CONTROL_CONTRAST)
            val accent = ensureContrast(rawAccent, surface, CONTROL_CONTRAST)
            return PlayerVisualPalette(
                primary = primary,
                secondary = secondary,
                accent = accent,
                surface = surface,
                surfaceVariant = surfaceVariant,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
                onAccent = bestContrastingText(accent),
            )
        }

        private const val TEXT_CONTRAST = 4.5f
        private const val CONTROL_CONTRAST = 3f
    }
}

/**
 * Local playback-only Material theme. Shader time never enters this function; transitions occur
 * only when the caller supplies a new artwork/preset palette.
 */
@Composable
fun PlayerVisualTheme(
    palette: PlayerVisualPalette,
    transitionMillis: Int = 500,
    content: @Composable () -> Unit,
) {
    val duration = transitionMillis.coerceIn(0, 2_000)
    val animation = tween<Color>(durationMillis = duration)
    val primary by animateColorAsState(palette.primary, animation, label = "playerVisualPrimary")
    val secondary by animateColorAsState(palette.secondary, animation, label = "playerVisualSecondary")
    val accent by animateColorAsState(palette.accent, animation, label = "playerVisualAccent")
    val surface by animateColorAsState(palette.surface, animation, label = "playerVisualSurface")
    val surfaceVariant by animateColorAsState(
        palette.surfaceVariant,
        animation,
        label = "playerVisualSurfaceVariant",
    )
    val onSurface by animateColorAsState(palette.onSurface, animation, label = "playerVisualOnSurface")
    val onSurfaceVariant by animateColorAsState(
        palette.onSurfaceVariant,
        animation,
        label = "playerVisualOnSurfaceVariant",
    )
    val onAccent by animateColorAsState(palette.onAccent, animation, label = "playerVisualOnAccent")
    val base = MaterialTheme.colorScheme

    MaterialTheme(
        colorScheme = base.copy(
            primary = accent,
            onPrimary = onAccent,
            secondary = secondary,
            onSecondary = bestContrastingText(secondary),
            tertiary = primary,
            onTertiary = bestContrastingText(primary),
            surface = surface,
            surfaceVariant = surfaceVariant,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
        ),
        content = content,
    )
}

/** WCAG-style contrast ratio for opaque UI colors. */
fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLum = relativeLuminance(foreground.opaque())
    val backgroundLum = relativeLuminance(background.opaque())
    return (max(foregroundLum, backgroundLum) + 0.05f) /
        (min(foregroundLum, backgroundLum) + 0.05f)
}

private fun ensureContrast(candidate: Color, background: Color, minimumRatio: Float): Color {
    val opaqueCandidate = candidate.opaque()
    if (contrastRatio(opaqueCandidate, background) >= minimumRatio) return opaqueCandidate
    val target = bestContrastingText(background)
    for (step in 1..20) {
        val adjusted = blend(opaqueCandidate, target, step / 20f)
        if (contrastRatio(adjusted, background) >= minimumRatio) return adjusted
    }
    return target
}

private fun bestContrastingText(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }

private fun relativeLuminance(color: Color): Float =
    0.2126f * linearChannel(color.red) +
        0.7152f * linearChannel(color.green) +
        0.0722f * linearChannel(color.blue)

private fun linearChannel(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

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
