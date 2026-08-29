package com.mediahub.core.ui.effects

import android.graphics.Bitmap

/**
 * Bitmap convenience wrapper around [ArtworkPaletteExtractor]. Downscales to a small sample
 * before histogramming so poster/backdrop-sized bitmaps cost almost nothing.
 */
object ArtworkPalette {

    private const val MAX_SAMPLE_SIDE = 64

    fun fromBitmap(source: Bitmap): VisualPalette {
        require(!source.isRecycled) { "source bitmap is recycled" }
        val longest = maxOf(source.width, source.height)
        val scale = maxOf(1, longest / MAX_SAMPLE_SIDE)
        val scaled = if (scale > 1) {
            Bitmap.createScaledBitmap(source, source.width / scale, source.height / scale, false)
        } else {
            source
        }
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== source) scaled.recycle()
        return ArtworkPaletteExtractor.extract(px)
    }
}
