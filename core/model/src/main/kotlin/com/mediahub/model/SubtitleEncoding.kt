package com.mediahub.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 字幕文本编码归一化（P1 字幕乱码切片）。
 *
 * 处理顺序：UTF-8 BOM → UTF-16 BOM → 严格 UTF-8 → UTF-16 无 BOM 启发式
 * → GB18030 → Big5 → ISO-8859-1 保底。纯 JVM，便于逐字节单测。
 */
object SubtitleEncoding {

    private val utf8 = Charsets.UTF_8
    private val utf16Le = Charsets.UTF_16LE
    private val utf16Be = Charsets.UTF_16BE
    private val gb18030 = Charset.forName("GB18030")
    private val big5 = Charset.forName("Big5")
    private val iso88591 = Charsets.ISO_8859_1

    /** 将常见字幕编码转为 UTF-8；已是 UTF-8 时保持原字节。 */
    fun normalizeToUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        if (hasPrefix(bytes, 0xEF, 0xBB, 0xBF)) {
            val body = bytes.copyOfRange(3, bytes.size)
            if (isValidUtf8(body)) return body
        }
        if (hasPrefix(bytes, 0xFF, 0xFE)) {
            decodeStrict(bytes, 2, utf16Le)?.let { return it.toByteArray(utf8) }
        }
        if (hasPrefix(bytes, 0xFE, 0xFF)) {
            decodeStrict(bytes, 2, utf16Be)?.let { return it.toByteArray(utf8) }
        }
        if (isValidUtf8(bytes)) return bytes
        if (looksLikeUtf16(bytes, littleEndian = true)) {
            decodeStrict(bytes, 0, utf16Le)?.let { return it.toByteArray(utf8) }
        }
        if (looksLikeUtf16(bytes, littleEndian = false)) {
            decodeStrict(bytes, 0, utf16Be)?.let { return it.toByteArray(utf8) }
        }
        decodeStrict(bytes, 0, gb18030)?.let { return it.toByteArray(utf8) }
        decodeStrict(bytes, 0, big5)?.let { return it.toByteArray(utf8) }
        return String(bytes, iso88591).toByteArray(utf8)
    }

    private fun hasPrefix(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }

    private fun isValidUtf8(bytes: ByteArray): Boolean = decodeStrict(bytes, 0, utf8) != null

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String? {
        if (offset > bytes.size) return null
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** UTF-16 无 BOM 的常见形态：ASCII/CJK 文本在奇数或偶数位大量出现 0x00。 */
    private fun looksLikeUtf16(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 4 || bytes.size % 2 != 0) return false
        val zeroAtHigh = if (littleEndian) 1 else 0
        val zeroAtLow = if (littleEndian) 0 else 1
        var highZeros = 0
        var lowZeros = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i + zeroAtHigh].toInt() == 0) highZeros++
            if (bytes[i + zeroAtLow].toInt() == 0) lowZeros++
            i += 2
        }
        val pairs = bytes.size / 2
        return highZeros * 2 >= pairs && lowZeros * 4 < pairs
    }
}
