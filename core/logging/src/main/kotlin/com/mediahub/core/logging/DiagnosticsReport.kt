package com.mediahub.core.logging

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 诊断报告的渲染与落盘（W6-110）。
 *
 * 日志在写入 [MemoryLogger] 时已经过一次 [Redactor]；这里在导出时对每一行**再脱敏一次**，
 * 于是即便有人绕过 MemoryLogger 直接把原文塞进 [LogBuffer]，导出的文件也不会带出秘密。
 * 渲染与写盘只用 java.io / java.time（minSdk 26），不依赖 Android。
 */
object DiagnosticsReport {

    /** 报告第一行的标题。 */
    const val TITLE: String = "MediaHub 诊断报告"

    /** 文件名前缀。 */
    const val FILE_PREFIX: String = "mediahub-diagnostics-"

    /** 文件名后缀。 */
    const val FILE_SUFFIX: String = ".txt"

    /** 写盘失败时对外给的稳定原因码。 */
    const val WRITE_FAILED: String = "diagnostics-write-failed"

    /** 失败原因（异常消息）保留的最大字符数。 */
    const val MAX_CAUSE_CHARS: Int = 160

    /** 报告末尾的说明。 */
    const val DISCLAIMER: String =
        "本报告在写入缓冲时已脱敏，导出时再逐行脱敏一次；密码、令牌与 Cookie 不会出现在这里。"

    private const val LOG_SEPARATOR = "---- 日志 ----"

    private const val NOTE_SEPARATOR = "---- 说明 ----"

    private val FILE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private val HEADER_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)

    private val WHITESPACE = Regex("""\s+""")

    /** 报告文件名：只随 instant 与 zone 变（同一秒导两次会撞名，由调用方决定要不要换目录）。 */
    fun fileName(instant: Instant, zone: ZoneId): String =
        FILE_PREFIX + FILE_TIME_FORMATTER.format(instant.atZone(zone)) + FILE_SUFFIX

    /** 渲染报告正文（不写盘）：头部 + 日志 + 说明；头部字段与每条日志都过一遍 [Redactor]。 */
    fun render(
        lines: List<String>,
        instant: Instant,
        zone: ZoneId,
        appVersion: String,
        device: String,
    ): String {
        val header = listOf(
            TITLE,
            "生成时间：" + HEADER_TIME_FORMATTER.withZone(zone).format(instant),
            "应用版本：" + redact(appVersion),
            "设备：" + redact(device),
            "日志条目：" + lines.size,
        )
        val body = lines.map { redact(it) }
        return (header + LOG_SEPARATOR + body + NOTE_SEPARATOR + DISCLAIMER).joinToString("\n")
    }

    /** 渲染并写入 directory（目录不存在就建）。 */
    fun write(
        directory: File,
        lines: List<String>,
        instant: Instant,
        zone: ZoneId,
        appVersion: String,
        device: String,
    ): WriteResult {
        val text = render(lines, instant, zone, appVersion, device)
        val target = File(directory, fileName(instant, zone))
        return try {
            directory.mkdirs()
            target.writeText(text, Charsets.UTF_8)
            WriteResult.Written(
                path = target.absolutePath,
                bytes = text.toByteArray(Charsets.UTF_8).size,
                entries = lines.size,
            )
        } catch (failure: Exception) {
            WriteResult.Failed(reason = WRITE_FAILED, causeMessage = describe(failure))
        }
    }

    /** 把异常消息净化成一句话：先脱敏、空白折叠成单空格、限长；没有消息就是 null。 */
    fun describe(failure: Throwable?): String? {
        val raw = failure?.message ?: return null
        val cleaned = redact(raw).replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_CAUSE_CHARS) return cleaned
        val cut = cleaned.take(MAX_CAUSE_CHARS)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun redact(value: String): String = Redactor.redact(value)

    /** 导出结果。 */
    sealed interface WriteResult {
        /** 已写入：绝对路径、字节数、日志条目数。 */
        data class Written(val path: String, val bytes: Int, val entries: Int) : WriteResult

        /** 写入失败：reason 是稳定码，causeMessage 可能为 null。 */
        data class Failed(val reason: String, val causeMessage: String?) : WriteResult
    }
}
