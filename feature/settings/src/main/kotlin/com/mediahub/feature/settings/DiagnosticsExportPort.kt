package com.mediahub.feature.settings

import com.mediahub.core.logging.DiagnosticsReport
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.StdoutLogger
import java.io.File
import java.time.Instant
import java.time.ZoneId

interface DiagnosticsExportPort {
    suspend fun export(): DiagnosticsExportOutcome
}

sealed interface DiagnosticsExportOutcome {
    data class Exported(val path: String, val bytes: Int, val entries: Int) : DiagnosticsExportOutcome
    data class Failed(val reason: String, val causeMessage: String?) : DiagnosticsExportOutcome
}

class FileDiagnosticsExportPort(
    private val buffer: LogBuffer,
    private val directory: File,
    private val appVersion: String,
    private val device: String,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val logger: Logger = StdoutLogger(),
) : DiagnosticsExportPort {
    override suspend fun export(): DiagnosticsExportOutcome {
        val written = DiagnosticsReport.write(directory, buffer.snapshot(), clock(), zone, appVersion, device)
        return when (written) {
            is DiagnosticsReport.WriteResult.Written -> {
                logger.i(LogTag.UI, "diagnostics-exported:entries=${written.entries}:bytes=${written.bytes}")
                DiagnosticsExportOutcome.Exported(written.path, written.bytes, written.entries)
            }
            is DiagnosticsReport.WriteResult.Failed -> {
                logger.w(LogTag.UI, "diagnostics-export-failed:${written.reason}")
                DiagnosticsExportOutcome.Failed(written.reason, written.causeMessage)
            }
        }
    }
}
