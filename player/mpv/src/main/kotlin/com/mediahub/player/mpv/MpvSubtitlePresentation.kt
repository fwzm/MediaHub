package com.mediahub.player.mpv

import com.mediahub.model.SubtitleStyle
import kotlin.math.roundToInt

/**
 * Presentation options for the pinned mpv 0.41 renderer, with no track/font/lifecycle changes.
 * Options: https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst
 * Types: https://github.com/mpv-player/mpv/blob/v0.41.0/sub/osd.c
 *
 * Native colors use r/g/b/a, with alpha 0 transparent and 1 opaque, not ASS's inverted alpha.
 * With a transparent background, drop shadow uses edgeColor. A visible background needs
 * background-box: mpv shares its background and shadow color, and shadow-offset then means
 * box padding. A separate edge-colored drop shadow plus visible background is not representable
 * by these options; background is retained, with the requested offset as box padding.
 */
internal object MpvSubtitlePresentation {
    fun options(style: SubtitleStyle, sizeSp: Int): Map<String, String> {
        // Match the existing Media3 baseScale clamp and the accepted SubtitleStyle ranges.
        val size = if (sizeSp <= 0) 18 else sizeSp.coerceIn(9, 54)
        val scale = style.textScale.finiteOr(1f).coerceIn(0.6f, 2f)
        val padding = style.bottomPaddingFraction.finiteOr(0.08f).coerceIn(0f, 0.4f)
        val edge = style.edgeType.takeIf { it in SubtitleStyle.EDGE_TYPE_NONE..SubtitleStyle.EDGE_TYPE_DROP_SHADOW }
            ?: SubtitleStyle.EDGE_TYPE_OUTLINE
        val hasBackground = (style.backgroundColor ushr 24) != 0
        val shadow = edge == SubtitleStyle.EDGE_TYPE_DROP_SHADOW
        val backColor = if (!hasBackground && shadow) style.edgeColor else style.backgroundColor
        return linkedMapOf(
            "sub-font-size" to (38.0 * size / 18.0 * scale.toDouble()).toString(),
            "sub-color" to rgba(style.textColor),
            "sub-outline-color" to rgba(style.edgeColor),
            "sub-back-color" to rgba(backColor),
            "sub-border-style" to if (hasBackground) "background-box" else "outline-and-shadow",
            "sub-outline-size" to if (edge == SubtitleStyle.EDGE_TYPE_OUTLINE) "1.65" else "0",
            "sub-shadow-offset" to if (shadow) "2" else "0",
            // mpv's margin option is an integer; a decimal string would be rejected.
            "sub-margin-y" to (720.0 * padding.toDouble()).roundToInt().toString(),
            // Preserve authored ASS by default; only the user's explicit toggle enables force.
            "sub-ass-override" to if (style.applyEmbeddedStyles) "no" else "force",
        )
    }

    private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default

    private fun rgba(argb: Int): String = listOf(16, 8, 0, 24).joinToString("/") { shift ->
        // Double.toString is locale independent, and preserves the 8-bit alpha normalization.
        (((argb ushr shift) and 0xff) / 255.0).toString()
    }
}
