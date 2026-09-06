package com.mediahub.core.ui.effects

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertOpaque(palette)
    }

    @Test
    fun `all extracted and derived colors are opaque`() {
        val pixels = intArrayOf(
            0xFFD32F2F.toInt(),
            0xFF2962FF.toInt(),
            0xFFFFD54F.toInt(),
            0xFF101010.toInt(),
        )

        assertOpaque(ArtworkPaletteExtractor.extract(pixels))
    }

    @Test
    fun `transparent padding cannot dominate visible artwork`() {
        val invisibleGreen = 0x0000FF00
        val visibleRed = 0xFFFF0000.toInt()
        val pixels = IntArray(1_001) { if (it == 1_000) visibleRed else invisibleGreen }

        val palette = ArtworkPaletteExtractor.extract(pixels)

        assertTrue("primary=${hex(palette.primary)}", red(palette.primary) > green(palette.primary))
        assertOpaque(palette)
    }

    @Test
    fun `all transparent pixels return stable fallback`() {
        val transparentGarbage = intArrayOf(0x0000FF00, 0x007F0000, 0x000000FF)

        assertEquals(VisualPalette.Fallback, ArtworkPaletteExtractor.extract(transparentGarbage))
    }

    @Test
    fun `sample dimensions cap longest side and retain a one pixel short side`() {
        assertEquals(64 to 64, ArtworkPalette.sampleDimensions(127, 127))
        assertEquals(1 to 64, ArtworkPalette.sampleDimensions(1, 8_192))
        assertEquals(64 to 32, ArtworkPalette.sampleDimensions(128, 64))
        assertEquals(64 to 63, ArtworkPalette.sampleDimensions(65, 64))
        assertEquals(64 to 32, ArtworkPalette.sampleDimensions(64, 32))
    }

    @Test
    fun `low alpha colors are ignored while threshold alpha is retained`() {
        val belowThresholdBlue = 0x7F0000FF
        val thresholdYellow = 0x80FFFF00.toInt()

        val palette = ArtworkPaletteExtractor.extract(
            IntArray(100) { if (it == 99) thresholdYellow else belowThresholdBlue },
        )

        assertTrue(red(palette.primary) > blue(palette.primary))
        assertNotEquals(VisualPalette.Fallback, palette)
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

    private fun assertOpaque(palette: VisualPalette) {
        listOf(palette.background, palette.primary, palette.secondary, palette.accent).forEach { color ->
            assertEquals("alpha of ${hex(color)}", 0xFF, color ushr 24)
        }
    }

    private fun pseudoRandomColor(seed: Int): Int {
        var x = seed * 2654435761L
        x = x xor (x ushr 13)
        x *= 1274126177L
        x = x xor (x ushr 16)
        return (0xFF000000.toInt() or ((x.toInt()) and 0xFFFFFF))
    }
}
