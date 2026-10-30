package com.mediahub.core.logging

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val instant: Instant = Instant.parse("2026-10-29T10:51:00Z")
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun newDirectory(): File = Files.createTempDirectory("mh-diag-report-").toFile()

    @Test
    fun the_report_has_a_header_the_lines_then_the_note_in_that_order() {
        val text = DiagnosticsReport.render(
            listOf("[I][UI] 第一条", "[W][NETWORK] 第二条"),
            instant, shanghai, "1.7.0", "Pixel",
        )
        val lines = text.split("\n")
        assertEquals(DiagnosticsReport.TITLE, lines.first())
        assertTrue(lines[1].startsWith("生成时间：2026-10-29 18:51:00"))
        assertEquals("应用版本：1.7.0", lines[2])
        assertEquals("设备：Pixel", lines[3])
        assertEquals("日志条目：2", lines[4])
        assertEquals("---- 日志 ----", lines[5])
        assertEquals("[I][UI] 第一条", lines[6])
        assertEquals("[W][NETWORK] 第二条", lines[7])
        assertEquals("---- 说明 ----", lines[8])
        assertEquals(DiagnosticsReport.DISCLAIMER, lines[9])
        assertEquals(10, lines.size)
        assertTrue(text.endsWith(DiagnosticsReport.DISCLAIMER))
    }

    @Test
    fun every_line_is_redacted_again_even_when_it_never_passed_the_memory_logger() {
        val text = DiagnosticsReport.render(
            listOf(
                "raw password=hunter2",
                "url https://user:pw@nas-a:8096/emby?api_key=abc123",
                "[I][UI] 来源 sync-a",
            ),
            instant, shanghai, "1.7.0", "Pixel",
        )
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("pw@"))
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains(Redactor.REDACTED))
        assertTrue(text.contains("来源 sync-a"))
    }

    @Test
    fun the_header_values_are_redacted_too() {
        val text = DiagnosticsReport.render(
            emptyList(), instant, shanghai, "1.7.0 token=abc123", "Pixel password=hunter2",
        )
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("应用版本："))
        assertTrue(text.contains("设备："))
    }

    @Test
    fun the_file_name_follows_the_instant_and_the_zone() {
        assertEquals(
            "mediahub-diagnostics-20261029-183000.txt",
            DiagnosticsReport.fileName(Instant.parse("2026-10-29T18:30:00Z"), utc),
        )
        assertEquals(
            "mediahub-diagnostics-20261030-023000.txt",
            DiagnosticsReport.fileName(Instant.parse("2026-10-29T18:30:00Z"), shanghai),
        )
        assertNotEquals(
            DiagnosticsReport.fileName(instant, utc),
            DiagnosticsReport.fileName(instant, shanghai),
        )
    }

    @Test
    fun write_creates_a_missing_directory_and_reports_bytes_and_entries() {
        val directory = File(newDirectory(), "nested/out")
        val lines = listOf("[I][UI] 一行")
        val result = DiagnosticsReport.write(directory, lines, instant, shanghai, "1.7.0", "Pixel")

        assertTrue(result is DiagnosticsReport.WriteResult.Written)
        val written = result as DiagnosticsReport.WriteResult.Written
        val file = File(directory, DiagnosticsReport.fileName(instant, shanghai))
        assertTrue(file.exists())
        assertTrue(directory.isDirectory)
        assertEquals(file.absolutePath, written.path)
        assertEquals(1, written.entries)
        val text = file.readText(Charsets.UTF_8)
        assertEquals(DiagnosticsReport.render(lines, instant, shanghai, "1.7.0", "Pixel"), text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, written.bytes)
    }

    @Test
    fun a_target_blocked_by_a_file_reports_the_stable_code_and_a_clean_message() {
        val blocker = File(newDirectory(), "blocked")
        blocker.writeText("x", Charsets.UTF_8)
        val directory = File(blocker, "diagnostics")

        val result = DiagnosticsReport.write(
            directory, listOf("[I][UI] 一行"), instant, shanghai, "1.7.0", "Pixel",
        )

        assertTrue(result is DiagnosticsReport.WriteResult.Failed)
        val failed = result as DiagnosticsReport.WriteResult.Failed
        assertEquals(DiagnosticsReport.WRITE_FAILED, failed.reason)
        assertFalse(failed.causeMessage.isNullOrBlank())
        assertTrue(failed.causeMessage!!.length <= DiagnosticsReport.MAX_CAUSE_CHARS)
    }

    @Test
    fun an_empty_buffer_still_produces_a_report_with_the_note() {
        val text = DiagnosticsReport.render(emptyList(), instant, shanghai, "1.7.0", "Pixel")
        assertTrue(text.contains("日志条目：0"))
        assertTrue(text.contains("---- 日志 ----"))
        assertTrue(text.endsWith(DiagnosticsReport.DISCLAIMER))
    }

    @Test
    fun describe_cleans_secrets_whitespace_and_length() {
        val cleaned = DiagnosticsReport.describe(
            IllegalStateException("cannot write to https://user:pw@nas-a:8096/emby?api_key=abc123\nline two"),
        )
        assertTrue(cleaned != null)
        assertFalse(cleaned!!.contains("pw@"))
        assertFalse(cleaned.contains("abc123"))
        assertFalse(cleaned.contains("\n"))
        assertEquals(
            DiagnosticsReport.MAX_CAUSE_CHARS,
            DiagnosticsReport.describe(IllegalStateException("x".repeat(400)))!!.length,
        )
        assertNull(DiagnosticsReport.describe(null))
        assertNull(DiagnosticsReport.describe(RuntimeException()))
        assertNull(DiagnosticsReport.describe(IllegalStateException("   ")))
    }
}
