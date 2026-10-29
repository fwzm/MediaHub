package com.mediahub.feature.settings

import com.mediahub.core.logging.DiagnosticsReport
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.MemoryLogger
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportPortTest {

    private val instant: Instant = Instant.parse("2026-10-29T10:51:00Z")
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun newDirectory(): File = Files.createTempDirectory("mh-diagnostics-port-").toFile()

    private fun port(
        buffer: LogBuffer,
        directory: File,
        logs: LogBuffer = LogBuffer(),
        clock: () -> Instant = { instant },
    ): DiagnosticsExportPort = FileDiagnosticsExportPort(
        buffer = buffer,
        directory = directory,
        appVersion = "1.7.0",
        device = "Pixel",
        clock = clock,
        zone = zone,
        logger = MemoryLogger(logs),
    )

    @Test
    fun export_writes_the_snapshot_under_the_given_directory() = runBlocking {
        val buffer = LogBuffer()
        buffer.append("[I][UI] 一行日志")
        val directory = newDirectory()

        val outcome = port(buffer, directory).export()

        assertTrue(outcome is DiagnosticsExportOutcome.Exported)
        val exported = outcome as DiagnosticsExportOutcome.Exported
        val file = File(directory, DiagnosticsReport.fileName(instant, zone))
        assertTrue(file.exists())
        assertEquals(file.absolutePath, exported.path)
        assertEquals(1, exported.entries)
        val text = file.readText(Charsets.UTF_8)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, exported.bytes)
        assertTrue(text.contains("一行日志"))
        assertTrue(text.contains(DiagnosticsReport.DISCLAIMER))
    }

    @Test
    fun the_information_line_carries_the_counts_and_never_the_directory() = runBlocking {
        val buffer = LogBuffer()
        buffer.append("[I][UI] 第一行")
        buffer.append("[W][NETWORK] 第二行")
        val logs = LogBuffer()
        val directory = newDirectory()

        port(buffer, directory, logs).export()

        val lines = logs.snapshot()
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("[I][UI] diagnostics-exported:entries=2:bytes="))
        assertFalse(lines[0].contains(directory.name))
        assertFalse(lines[0].contains(directory.absolutePath))
        assertFalse(lines[0].contains(DiagnosticsReport.FILE_PREFIX))
    }

    @Test
    fun a_blocked_directory_reports_the_stable_code_and_one_warning_line() = runBlocking {
        val parent = newDirectory()
        val blocker = File(parent, "blocked")
        blocker.writeText("x", Charsets.UTF_8)
        val directory = File(blocker, "diagnostics")
        val buffer = LogBuffer()
        buffer.append("[I][UI] 一行")
        val logs = LogBuffer()

        val outcome = port(buffer, directory, logs).export()

        assertTrue(outcome is DiagnosticsExportOutcome.Failed)
        val failed = outcome as DiagnosticsExportOutcome.Failed
        assertEquals(DiagnosticsReport.WRITE_FAILED, failed.reason)
        assertFalse(failed.causeMessage.isNullOrBlank())
        assertTrue(failed.causeMessage!!.length <= DiagnosticsReport.MAX_CAUSE_CHARS)
        val lines = logs.snapshot()
        assertEquals(1, lines.size)
        assertEquals("[W][UI] diagnostics-export-failed:" + DiagnosticsReport.WRITE_FAILED, lines[0])
        assertFalse(lines[0].contains("blocked"))
    }

    @Test
    fun two_exports_two_seconds_apart_leave_two_files() = runBlocking {
        var now = instant
        val buffer = LogBuffer()
        buffer.append("[I][UI] 一行")
        val directory = newDirectory()
        val exporting = port(buffer, directory, clock = { now })

        val first = exporting.export()
        now = instant.plusSeconds(2)
        val second = exporting.export()

        val firstExported = first as DiagnosticsExportOutcome.Exported
        val secondExported = second as DiagnosticsExportOutcome.Exported
        assertNotEquals(firstExported.path, secondExported.path)
        assertTrue(File(firstExported.path).exists())
        assertTrue(File(secondExported.path).exists())
        assertEquals(DiagnosticsReport.fileName(instant, zone), File(firstExported.path).name)
        assertEquals(DiagnosticsReport.fileName(instant.plusSeconds(2), zone), File(secondExported.path).name)
    }
}
