package com.mediahub.core.ui.effects

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteExtractorTest {

    @Test
    fun `solid saturated image yields that color as primary and dark background`() {
        val red = 0xFFD32F2F.toInt()
        val palette = ArtworkPaletteExtractor.extract(IntArray(1024) { red })

        val primaryLum = luminance(palette.primary)
        assertTrue("primary=${hex(palette.primary)}", primaryLum > 0.2f)
        assertTrue("bg=${hex(palette.background)}", luminance(palette.background) < primaryLum)
        assertEquals(0f, hueOf(red), 30f)
    }

    @Test
    fun `two distinct hues are separated into primary and secondary`() {
        val blue = 0xFF2962FF.toInt()
        val orange = 0xFFFF6D00.toInt()
        val pixels = IntArray(2048) { if (it % 2 == 0) blue else orange }
        val palette = ArtworkPaletteExtractor.extract(pixels)

        val dist = ArtworkPaletteExtractor.hueDistance(
            hueOf(palette.primary),
            hueOf(palette.secondary),
        )
        assertTrue("primary=${hex(palette.primary)} secondary=${hex(palette.secondary)} dist=$dist", dist > 40f)
    }

    @Test
    fun `achromatic image still yields a usable palette`() {
        val pixels = IntArray(1024) { if (it % 2 == 0) 0xFFF5F5F5.toInt() else 0xFF222222.toInt() }
        val palette = ArtworkPaletteExtractor.extract(pixels)
        assertTrue(palette.primary != 0)
        assertTrue(palette.secondary != 0)
        assertTrue(palette.accent != 0)
    }

    @Test
    fun `deterministic for the same input`() {
        val pixels = IntArray(4096) { pseudoRandomColor(it) }
        assertEquals(ArtworkPaletteExtractor.extract(pixels), ArtworkPaletteExtractor.extract(pixels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty input is rejected`() {
        ArtworkPaletteExtractor.extract(IntArray(0))
    }

    @Test
    fun `hue helper handles wraparound`() {
        assertEquals(20f, ArtworkPaletteExtractor.hueDistance(10f, 360f - 10f), 0.01f)
        assertEquals(90f, ArtworkPaletteExtractor.hueDistance(0f, 90f), 0.01f)
        assertEquals(180f, ArtworkPaletteExtractor.hueDistance(0f, 180f), 0.01f)
    }

    private fun hueOf(color: Int): Float = ArtworkPaletteExtractor.hueDeg(
        red(color).toFloat(),
        green(color).toFloat(),
        blue(color).toFloat(),
    )

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF

    private fun luminance(color: Int): Float = (
        0.2126f * red(color) + 0.7152f * green(color) + 0.0722f * blue(color)
        ) / 255f

    private fun hex(color: Int) = "0x%08X".format(color)

    private fun pseudoRandomColor(seed: Int): Int {
        var x = seed * 2654435761L
        x = x xor (x ushr 13)
        x *= 1274126177L
        x = x xor (x ushr 16)
        return (0xFF000000.toInt() or ((x.toInt()) and 0xFFFFFF))
    }
}
