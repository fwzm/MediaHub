package com.mediahub.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 字幕文本编码（审计条目 66：部分字幕乱码）。
 *
 * 只描述"探测结论"，不承诺转码能力：真正把字节变成文本由 [SubtitleCharsetDetector.decode] 负责。
 */
enum class SubtitleCharset(
    /** 人类可读名（进日志，不含任何路径/凭据）。 */
    val label: String,
    /** 对应的 JVM/Android charset 名；[UNKNOWN] 没有可用 charset。 */
    val charsetName: String?,
) {
    UTF_8("UTF-8", "UTF-8"),
    UTF_16_LE("UTF-16LE", "UTF-16LE"),
    UTF_16_BE("UTF-16BE", "UTF-16BE"),
    GB18030("GB18030", "GB18030"),
    BIG5("Big5", "Big5"),
    UNKNOWN("未知", null),
}

/**
 * 字幕字节编码探测（纯 JVM，无 Android 依赖）。
 *
 * 纪律：探测是**有据的启发式**，不是猜测——顺序固定、可复现，并且：
 * - BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）；
 * - 无 BOM 时先试严格 UTF-8，失败才进入中文双字节候选；
 * - 候选之间用"常用汉字命中数"打分，同分时确定性地偏向 GB18030（大陆字幕主流）；
 * - 全失败返回 [SubtitleCharset.UNKNOWN]，调用方必须按"未知"降级处理，不得假装成功。
 *
 * 纯函数、不抛异常（除 OOM 之类不可恢复错误）：调用方在下载路径上，崩溃比乱码更糟。
 */
object SubtitleCharsetDetector {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** 探测窗口：只看前 64 KiB，避免整片大文件做多轮解码。 */
    private const val PROBE_LIMIT_BYTES = 64 * 1024

    private val GB18030: Charset = Charset.forName("GB18030")
    private val BIG5: Charset = Charset.forName("Big5")

    /**
     * 探测编码。空输入按 UTF-8 处理（空字幕文本无歧义）。
     */
    fun detect(bytes: ByteArray): SubtitleCharset {
        if (bytes.isEmpty()) return SubtitleCharset.UTF_8

        when {
            bytes.startsWith(UTF8_BOM) -> return SubtitleCharset.UTF_8
            bytes.startsWith(UTF16_LE_BOM) -> return SubtitleCharset.UTF_16_LE
            bytes.startsWith(UTF16_BE_BOM) -> return SubtitleCharset.UTF_16_BE
        }

        val window = probeWindow(bytes)
        if (isStrictlyDecodable(window, Charsets.UTF_8)) return SubtitleCharset.UTF_8

        // 无 BOM 且不是合法 UTF-8：进入中文双字节候选。
        // 候选必须**严格可解码**（含 0xFF 之类的非法字节直接淘汰），否则"解出来的乱码"会被误判成命中。
        val gbText = strictDecode(window, GB18030)
        val big5Text = strictDecode(window, BIG5)
        val gbScore = gbText?.let(::cjkScore) ?: 0
        val big5Score = big5Text?.let(::cjkScore) ?: 0

        return when {
            gbScore == 0 && big5Score == 0 -> SubtitleCharset.UNKNOWN
            // 同分偏向 GB18030：大陆字幕占绝对多数，且 Big5 字节常能"解成"生僻 GB 字。
            gbScore >= big5Score -> SubtitleCharset.GB18030
            else -> SubtitleCharset.BIG5
        }
    }

    /**
     * 按探测结果解码成文本；无法识别时返回 UTF-8 宽松解码（含替换字符），保证调用方总能拿到可渲染文本。
     */
    fun decode(bytes: ByteArray): String {
        val detected = detect(bytes)
        val charset = detected.charsetName?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        } ?: Charsets.UTF_8
        return String(stripBom(bytes, detected), charset)
    }

    private fun probeWindow(bytes: ByteArray): ByteArray =
        if (bytes.size <= PROBE_LIMIT_BYTES) bytes else bytes.copyOf(PROBE_LIMIT_BYTES)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun stripBom(bytes: ByteArray, charset: SubtitleCharset): ByteArray = when (charset) {
        SubtitleCharset.UTF_8 -> if (bytes.startsWith(UTF8_BOM)) bytes.copyOfRange(3, bytes.size) else bytes
        SubtitleCharset.UTF_16_LE -> if (bytes.startsWith(UTF16_LE_BOM)) bytes.copyOfRange(2, bytes.size) else bytes
        SubtitleCharset.UTF_16_BE -> if (bytes.startsWith(UTF16_BE_BOM)) bytes.copyOfRange(2, bytes.size) else bytes
        else -> bytes
    }

    private fun isStrictlyDecodable(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** 严格解码；含非法/不可映射字节时返回 null（候选被淘汰，而不是产出替换字符）。 */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * 常用汉字命中数：落在 CJK 统一表意文字基本区（U+4E00..U+9FFF）的字符计 1 分。
     *
     * 只用"是否有大量汉字"这一条粗判据，避免把编码探测做成一张不可维护的启发式表；
     * 区分繁简/地区属于产品决策（PENDING），v1 不做。
     */
    private fun cjkScore(text: String): Int = text.count { it in '\u4E00'..'\u9FFF' }
}
