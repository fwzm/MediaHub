package com.mediahub.core.ui.effects

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Bitmap convenience wrapper around [ArtworkPaletteExtractor]. Downscales to a small sample
 * before histogramming so poster/backdrop-sized bitmaps cost almost nothing.
 */
object ArtworkPalette {

    private const val MAX_SAMPLE_SIDE = 64

    fun fromBitmap(source: Bitmap): VisualPalette {
        require(!source.isRecycled) { "source bitmap is recycled" }
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) {
                "hardware bitmap could not be copied to ARGB_8888"
            }
        } else {
            source
        }
        val (targetWidth, targetHeight) = sampleDimensions(readable.width, readable.height)
        val sampled = if (readable.width != targetWidth || readable.height != targetHeight) {
            Bitmap.createScaledBitmap(readable, targetWidth, targetHeight, false)
        } else {
            readable
        }

        return try {
            val pixels = IntArray(sampled.width * sampled.height)
            sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
            ArtworkPaletteExtractor.extract(pixels)
        } finally {
            if (sampled !== readable) sampled.recycle()
            if (readable !== source) readable.recycle()
        }
    }

    /** Returns a bounded sample size while preserving aspect ratio and a minimum one-pixel side. */
    internal fun sampleDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "bitmap dimensions must be positive" }
        val longest = maxOf(width, height)
        if (longest <= MAX_SAMPLE_SIDE) return width to height
        val ratio = MAX_SAMPLE_SIDE.toFloat() / longest.toFloat()
        return maxOf(1, (width * ratio).roundToInt()) to
            maxOf(1, (height * ratio).roundToInt())
    }
}
