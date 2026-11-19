package com.mediahub.model

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleEncodingTest {

    @Test
    fun `utf8 text is preserved`() {
        val bytes = "中文字幕\n第二行".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, SubtitleEncoding.normalizeToUtf8(bytes))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val text = "字幕".toByteArray(Charsets.UTF_8)
        assertArrayEquals(text, SubtitleEncoding.normalizeToUtf8(bom + text))
    }

    @Test
    fun `gbk text is converted to utf8`() {
        val text = "中文字幕\n第二行"
        val bytes = text.toByteArray(Charset.forName("GBK"))
        assertEquals(text, String(SubtitleEncoding.normalizeToUtf8(bytes), Charsets.UTF_8))
    }

    @Test
    fun `utf16 little endian bom is converted to utf8`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + "字幕".toByteArray(Charset.forName("UTF-16LE"))
        assertEquals("字幕", String(SubtitleEncoding.normalizeToUtf8(bytes), Charsets.UTF_8))
    }
}
