package com.mediahub.core.logging

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders and writes a redacted diagnostic report without depending on Android APIs. */
object DiagnosticsReport {
    const val TITLE: String = "MediaHub 诊断报告"
    const val FILE_PREFIX: String = "mediahub-diagnostics-"
    const val FILE_SUFFIX: String = ".txt"
    const val WRITE_FAILED: String = "diagnostics-write-failed"
    const val MAX_CAUSE_CHARS: Int = 160
    const val DISCLAIMER: String =
        "本报告在写入缓冲时已脱敏，导出时再逐行脱敏一次；密码、令牌与 Cookie 不会出现在这里。"

    private const val LOG_SEPARATOR = "---- 日志 ----"
    private const val NOTE_SEPARATOR = "---- 说明 ----"
    private val fileTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    private val headerTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
    private val whitespace = Regex("\\s+")

    fun fileName(instant: Instant, zone: ZoneId): String =
        FILE_PREFIX + fileTimeFormatter.format(instant.atZone(zone)) + FILE_SUFFIX

    fun render(
        lines: List<String>,
        instant: Instant,
        zone: ZoneId,
        appVersion: String,
        device: String,
    ): String {
        val header = listOf(
            TITLE,
            "生成时间：" + headerTimeFormatter.withZone(zone).format(instant),
            "应用版本：" + redact(appVersion),
            "设备：" + redact(device),
            "日志条目：" + lines.size,
        )
        val body = lines.map(::redact)
        return (header + LOG_SEPARATOR + body + NOTE_SEPARATOR + DISCLAIMER).joinToString("\n")
    }

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
            WriteResult.Failed(WRITE_FAILED, describe(failure))
        }
    }

    fun describe(failure: Throwable?): String? {
        val raw = failure?.message ?: return null
        val cleaned = redact(raw).replace(whitespace, " ").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_CAUSE_CHARS) return cleaned
        val cut = cleaned.take(MAX_CAUSE_CHARS)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun redact(value: String): String = Redactor.redact(value)

    sealed interface WriteResult {
        data class Written(val path: String, val bytes: Int, val entries: Int) : WriteResult
        data class Failed(val reason: String, val causeMessage: String?) : WriteResult
    }
}
