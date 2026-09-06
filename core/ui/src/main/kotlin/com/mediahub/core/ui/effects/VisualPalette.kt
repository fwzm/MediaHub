package com.mediahub.core.ui.effects

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ARGB color quad driving the shader palette. All colors are opaque straight-alpha values.
 */
data class VisualPalette(
    val background: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
) {
    companion object {
        /** Stable cinema-safe palette used when artwork has no usable opaque pixels. */
        val Fallback = VisualPalette(
            background = 0xFF0C0F1E.toInt(),
            primary = 0xFF9FB8FF.toInt(),
            secondary = 0xFF2A2554.toInt(),
            accent = 0xFFC86BFF.toInt(),
        )
    }
}

/** Internal scoring candidate: one 4-bit/channel histogram bucket. */
private data class PaletteCandidate(
    val key: Int,
    val color: Int,
    val score: Float,
    val sat: Float,
    val lum: Float,
    val hue: Float,
)

/**
 * Pure, allocation-light palette extraction from raw ARGB pixels.
 *
 * Deterministic by construction: buckets are quantized to 4 bits/channel and candidates are
 * ordered by score with the bucket key as tie-breaker, so the same artwork always yields the
 * same palette. The Bitmap convenience wrapper lives in [ArtworkPalette]; keep this file free
 * of android.graphics imports so the core logic stays JVM-testable.
 */
object ArtworkPaletteExtractor {

    /**
     * Extracts a [VisualPalette] from ARGB pixels.
     *
     * `primary` is the most vivid dominant color, `secondary` a distinct supporting color,
     * `accent` the most saturated remaining candidate. `background` is derived from the
     * primary's luminance so capsules stay readable over any artwork.
     */
    fun extract(pixels: IntArray): VisualPalette {
        require(pixels.isNotEmpty()) { "pixels must not be empty" }

        // key = r4 shl 8 or g4 shl 4 or b4, value = [count, rSum, gSum, bSum]
        val sums = HashMap<Int, LongArray>(256)
        for (c in pixels) {
            // Transparent artwork padding frequently contains arbitrary RGB values. It must
            // not be allowed to dominate the visible palette.
            if ((c ushr 24) < MIN_ARTWORK_ALPHA) continue
            val key = ((c ushr 20) and 0xF) shl 8 or (((c ushr 12) and 0xF) shl 4) or ((c ushr 4) and 0xF)
            val acc = sums.getOrPut(key) { LongArray(4) }
            acc[0]++
            acc[1] += ((c shr 16) and 0xFF).toLong()
            acc[2] += ((c shr 8) and 0xFF).toLong()
            acc[3] += (c and 0xFF).toLong()
        }
        if (sums.isEmpty()) return VisualPalette.Fallback
        val total = sums.values.sumOf { it[0] }.coerceAtLeast(1L).toFloat()

        val candidates = sums.map { (key, acc) ->
            val n = acc[0].coerceAtLeast(1L).toFloat()
            val r = acc[1] / n
            val g = acc[2] / n
            val b = acc[3] / n
            val maxc = maxOf(r, g, b)
            val minc = minOf(r, g, b)
            val sat = if (maxc <= 0f) 0f else (maxc - minc) / maxc
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            val vividProximity = (1f - abs(lum - 0.5f) * 1.4f).coerceIn(0.1f, 1f)
            val coverage = acc[0].toFloat() / total
            val score = sqrt(coverage) * (0.20f + sat) * (0.35f + 0.65f * vividProximity)
            PaletteCandidate(key, packRgb(r, g, b), score, sat, lum, hueDeg(r, g, b))
        }.sortedWith(compareByDescending<PaletteCandidate> { it.score }.thenBy { it.key })

        val primary = candidates.first()

        val secondary = candidates.firstOrNull {
            it !== primary && (hueDistance(it.hue, primary.hue) > HUE_SEP_DEG || abs(it.lum - primary.lum) > LUM_SEP)
        } ?: deriveShifted(primary, darker = true)

        val accent = candidates
            .filter { it !== primary && it !== secondary }
            .maxByOrNull { it.sat * (0.3f + it.score) }
            ?.takeIf { it.sat > 0.10f }
            ?: deriveShifted(primary, darker = false)

        val background = if (primary.lum > LIGHT_LUM_THRESHOLD) {
            mixTowards(primary.color, target = 0xFFF5F3EF.toInt(), weight = 0.85f)
        } else {
            scaleRgb(primary.color, factor = 0.14f)
        }

        return VisualPalette(
            background = background,
            primary = primary.color,
            secondary = secondary.color,
            accent = accent.color,
        )
    }

    /** Hue of an RGB triple in degrees [0, 360); 0 for achromatic input. */
    internal fun hueDeg(r: Float, g: Float, b: Float): Float {
        val maxc = maxOf(r, g, b)
        val minc = minOf(r, g, b)
        val delta = maxc - minc
        if (delta <= 0f) return 0f
        val hue = when (maxc) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    internal fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun deriveShifted(c: PaletteCandidate, darker: Boolean): PaletteCandidate {
        val color = if (darker) scaleRgb(c.color, 0.45f) else scaleRgb(c.color, 1.6f)
        val r = ((color shr 16) and 0xFF).toFloat()
        val g = ((color shr 8) and 0xFF).toFloat()
        val b = (color and 0xFF).toFloat()
        return PaletteCandidate(key = -1, color = color, score = 0f, sat = c.sat, lum = 0f, hue = hueDeg(r, g, b))
    }

    private fun packRgb(r: Float, g: Float, b: Float): Int =
        OPAQUE_ALPHA or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)

    private fun scaleRgb(color: Int, factor: Float): Int {
        fun ch(v: Int) = (v * factor).toInt().coerceIn(0, 255)
        return OPAQUE_ALPHA or
            (ch((color shr 16) and 0xFF) shl 16) or
            (ch((color shr 8) and 0xFF) shl 8) or
            ch(color and 0xFF)
    }

    private fun mixTowards(color: Int, target: Int, weight: Float): Int {
        fun mix(shift: Int): Int {
            val from = (color shr shift) and 0xFF
            val to = (target shr shift) and 0xFF
            return (from + ((to - from) * weight).toInt()).coerceIn(0, 255)
        }
        return OPAQUE_ALPHA or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private const val HUE_SEP_DEG = 40f
    private const val LUM_SEP = 0.28f
    private const val LIGHT_LUM_THRESHOLD = 0.55f
    private const val MIN_ARTWORK_ALPHA = 0x80
    private const val OPAQUE_ALPHA = -0x1000000
}
