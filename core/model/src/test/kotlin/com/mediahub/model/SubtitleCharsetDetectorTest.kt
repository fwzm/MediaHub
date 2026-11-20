package com.mediahub.model

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕编码探测回归（审计条目 66：部分字幕乱码）。
 *
 * 断言的是"真实字节 → 正确文本"：夹具用 JDK 自带 charset 现场编码，
 * 覆盖 UTF-8（含 BOM）、UTF-16LE/BE（BOM）、GB18030（简体）、Big5（繁体）。
 */
class SubtitleCharsetDetectorTest {

    private val simplified = "你好，这是字幕测试。第一行，第二行。"
    private val traditional = "繁體中文字幕測試，謝謝觀看。"

    private fun encode(text: String, charset: String): ByteArray = text.toByteArray(Charset.forName(charset))

    private fun utf8Bom(bytes: ByteArray): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes

    private fun utf16LeBom(bytes: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes

    private fun utf16BeBom(bytes: ByteArray): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + bytes

    @Test
    fun `plain utf8 subtitle is detected as utf8 and decodes unchanged`() {
        val bytes = encode(simplified, "UTF-8")
        assertEquals(SubtitleCharset.UTF_8, SubtitleCharsetDetector.detect(bytes))
        assertEquals(simplified, SubtitleCharsetDetector.decode(bytes))
    }

    @Test
    fun `utf8 with bom keeps text and strips the bom`() {
        val bytes = utf8Bom(encode(simplified, "UTF-8"))
        assertEquals(SubtitleCharset.UTF_8, SubtitleCharsetDetector.detect(bytes))
        assertEquals(simplified, SubtitleCharsetDetector.decode(bytes))
    }

    @Test
    fun `utf16 little and big endian bom are detected and decoded`() {
        val le = utf16LeBom(encode(simplified, "UTF-16LE"))
        val be = utf16BeBom(encode(simplified, "UTF-16BE"))
        assertEquals(SubtitleCharset.UTF_16_LE, SubtitleCharsetDetector.detect(le))
        assertEquals(simplified, SubtitleCharsetDetector.decode(le))
        assertEquals(SubtitleCharset.UTF_16_BE, SubtitleCharsetDetector.detect(be))
        assertEquals(simplified, SubtitleCharsetDetector.decode(be))
    }

    @Test
    fun `gb18030 encoded subtitle decodes to the original simplified text`() {
        val bytes = encode(simplified, "GB18030")
        assertEquals(SubtitleCharset.GB18030, SubtitleCharsetDetector.detect(bytes))
        assertEquals(simplified, SubtitleCharsetDetector.decode(bytes))
        // 旧行为（一律按 UTF-8 解）会产出替换字符，这就是用户看到的乱码。
        assertTrue(String(bytes, Charsets.UTF_8).contains('\uFFFD'))
    }

    @Test
    fun `big5 encoded subtitle decodes to the original traditional text`() {
        val bytes = encode(traditional, "Big5")
        assertEquals(SubtitleCharset.BIG5, SubtitleCharsetDetector.detect(bytes))
        assertEquals(traditional, SubtitleCharsetDetector.decode(bytes))
        assertTrue(String(bytes, Charsets.UTF_8).contains('\uFFFD'))
    }

    @Test
    fun `undecodable bytes are reported unknown and never throw`() {
        val bytes = byteArrayOf(0x81.toByte(), 0x40, 0xFE.toByte(), 0xFE.toByte(), 0xFF.toByte())
        val detected = SubtitleCharsetDetector.detect(bytes)
        assertEquals(SubtitleCharset.UNKNOWN, detected)
        // 未知也必须给出可渲染文本（替换字符），调用方据此降级而不是崩溃。
        assertFalse(SubtitleCharsetDetector.decode(bytes).isEmpty())
    }

    @Test
    fun `empty input is treated as utf8`() {
        assertEquals(SubtitleCharset.UTF_8, SubtitleCharsetDetector.detect(ByteArray(0)))
        assertEquals("", SubtitleCharsetDetector.decode(ByteArray(0)))
    }
}
