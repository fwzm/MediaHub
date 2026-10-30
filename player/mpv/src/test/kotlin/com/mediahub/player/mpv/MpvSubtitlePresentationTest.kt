package com.mediahub.player.mpv

import com.mediahub.model.SubtitleStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSubtitlePresentationTest {
    private fun options(style: SubtitleStyle, size: Int): Map<String, String> =
        MpvSubtitlePresentation.options(style, size).also {
            assertTrue("Native presentation must expose all expected options", it.keys.containsAll(setOf(
                "sub-font-size", "sub-color", "sub-outline-color", "sub-back-color", "sub-border-style",
                "sub-outline-size", "sub-shadow-offset", "sub-margin-y", "sub-ass-override")))
        }

    @Test
    fun `default style preserves authored ASS and emits accepted native option types`() {
        val options = options(SubtitleStyle(), 18)
        assertEquals(38.0, options.getValue("sub-font-size").toDouble(), 0.00001)
        assertEquals("58", options["sub-margin-y"])
        assertEquals("1.0/1.0/1.0/1.0", options["sub-color"])
        assertEquals("0.0/0.0/0.0/0.0", options["sub-back-color"])
        assertEquals("1.65", options["sub-outline-size"])
        assertEquals("0", options["sub-shadow-offset"])
        assertEquals("outline-and-shadow", options["sub-border-style"])
        assertEquals("no", options["sub-ass-override"])
        assertFalse(options.containsKey("sub-ass"))
        assertFalse(options.containsKey("sub-font"))
        assertFalse(options.containsKey("sub-scale"))
    }

    @Test
    fun `size scale and padding boundaries yield finite clamped native values`() {
        data class Case(val size: Int, val scale: Float, val padding: Float, val font: Double, val margin: Int)
        val cases = listOf(
            Case(24, 1.5f, 0.25f, 76.0, 180),
            Case(0, 1f, 0.08f, 38.0, 58),
            Case(Int.MIN_VALUE, 1f, 0.08f, 38.0, 58),
            Case(1, 0f, -1f, 11.4, 0),
            Case(Int.MAX_VALUE, 3f, 1f, 228.0, 288),
        )
        for (case in cases) {
            val options = options(
                SubtitleStyle(textScale = case.scale, bottomPaddingFraction = case.padding), case.size)
            assertEquals(case.toString(), case.font, options.getValue("sub-font-size").toDouble(), 0.00001)
            assertEquals(case.toString(), case.margin, options.getValue("sub-margin-y").toInt())
        }
    }

    @Test
    fun `nonfinite preferences fall back to canonical defaults rather than serialize invalid values`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val options = options(
                SubtitleStyle(textScale = value, bottomPaddingFraction = value), 18)
            assertEquals(38.0, options.getValue("sub-font-size").toDouble(), 0.00001)
            assertEquals("58", options["sub-margin-y"])
            assertFalse(options.values.any { it.contains("NaN") || it.contains("Infinity") })
        }
    }

    @Test
    fun `ARGB uses red green blue then noninverted alpha even under a decimal comma locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val options = options(
                SubtitleStyle(textColor = 0x80402010.toInt(), edgeColor = 0xFF0000FF.toInt(),
                    backgroundColor = 0x4000FF00), 18)
            val components = options.getValue("sub-color").split('/').map(String::toDouble)
            assertEquals(4, components.size)
            assertEquals(0.25098039215686274, components[0], 0.000000000001)
            assertEquals(0.12549019607843137, components[1], 0.000000000001)
            assertEquals(0.06274509803921569, components[2], 0.000000000001)
            assertEquals(0.5019607843137255, components[3], 0.000000000001)
            assertEquals("0.0/0.0/1.0/1.0", options["sub-outline-color"])
            assertEquals("0.0/1.0/0.0/0.25098039215686274", options["sub-back-color"])
            assertTrue(options.values.none { ',' in it })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `transparent backgrounds preserve transparency and drop shadow takes the edge color`() {
        val transparent = SubtitleStyle(backgroundColor = 0x00FF0000, edgeType = SubtitleStyle.EDGE_TYPE_NONE)
        val plain = options(transparent, 18)
        assertEquals("1.0/0.0/0.0/0.0", plain["sub-back-color"])
        assertEquals("0", plain["sub-outline-size"])
        assertEquals("0", plain["sub-shadow-offset"])
        val shadow = options(
            transparent.copy(edgeType = SubtitleStyle.EDGE_TYPE_DROP_SHADOW, edgeColor = 0x800000FF.toInt()), 18)
        assertEquals("outline-and-shadow", shadow["sub-border-style"])
        assertEquals("0.0/0.0/1.0/0.5019607843137255", shadow["sub-back-color"])
        assertEquals("2", shadow["sub-shadow-offset"])
    }

    @Test
    fun `visible background preserves its color while native shadow offset becomes box padding`() {
        val options = options(
            SubtitleStyle(backgroundColor = 0x8000FF00.toInt(), edgeColor = 0xFFFF0000.toInt(),
                edgeType = SubtitleStyle.EDGE_TYPE_DROP_SHADOW), 18)
        assertEquals("background-box", options["sub-border-style"])
        assertEquals("0.0/1.0/0.0/0.5019607843137255", options["sub-back-color"])
        assertEquals("1.0/0.0/0.0/1.0", options["sub-outline-color"])
        assertEquals("2", options["sub-shadow-offset"])
    }

    @Test
    fun `explicit user override forces styles without stripping ASS or altering fonts`() {
        val options = options(SubtitleStyle(applyEmbeddedStyles = false, edgeType = 99), 18)
        assertEquals("force", options["sub-ass-override"])
        assertEquals("1.65", options["sub-outline-size"])
        assertFalse(options.containsKey("sub-ass"))
        assertFalse(options.values.contains("strip"))
        assertFalse(options.keys.any { it == "sub-font" || it.startsWith("sub-fonts-") || it == "sub-font-provider" })
    }
}
