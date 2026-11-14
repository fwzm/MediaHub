package com.mediahub.core.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuCodecTest {

    private val sample = listOf(
        DanmakuComment(
            id = "a",
            timeMs = 1200L,
            text = "first",
            mode = DanmakuMode.SCROLL,
            colorArgb = 0xFFFFFFFF.toInt(),
            fontSizeSp = 25,
            source = "dandanplay",
        ),
        DanmakuComment(
            id = "b",
            timeMs = 3000L,
            text = "second",
            mode = DanmakuMode.TOP,
            colorArgb = 0xFFFF0000.toInt(),
            fontSizeSp = 18,
            source = "local",
        ),
    )

    @Test
    fun `round trip preserves all fields`() {
        val decoded = DanmakuCodec.decode(DanmakuCodec.encode(sample))

        assertEquals(sample, decoded)
    }

    @Test
    fun `decode skips malformed lines and sanitizes tabs and newlines`() {
        val dirty = sample[0].copy(text = "line1\tline2\nline3")
        val encoded = DanmakuCodec.encode(listOf(dirty)) + "\nbroken-line\n"

        val decoded = DanmakuCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("line1 line2 line3", decoded[0].text)
    }

    @Test
    fun `decode of empty or foreign payload is empty`() {
        assertTrue(DanmakuCodec.decode("").isEmpty())
        assertTrue(DanmakuCodec.decode("some random text").isEmpty())
    }
}
