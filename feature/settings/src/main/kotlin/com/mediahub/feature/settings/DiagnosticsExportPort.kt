package com.mediahub.feature.settings

import com.mediahub.core.logging.DiagnosticsReport
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.StdoutLogger
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 诊断日志导出出口（W6-110）：把内存里的日志环形缓冲写成一份可以交给别人看的文件。 */
interface DiagnosticsExportPort {
    suspend fun export(): DiagnosticsExportOutcome
}

/** 导出结果。 */
sealed interface DiagnosticsExportOutcome {
    /** 已写出：绝对路径、字节数、条目数。 */
    data class Exported(val path: String, val bytes: Int, val entries: Int) : DiagnosticsExportOutcome

    /** 写不出去：reason 是稳定码（见 DiagnosticsReport.WRITE_FAILED），causeMessage 可能为 null。 */
    data class Failed(val reason: String, val causeMessage: String?) : DiagnosticsExportOutcome
}

/**
 * 真实现：读 [LogBuffer.snapshot]，交给 [DiagnosticsReport] 渲染与写盘。
 *
 * 日志行只记条目数与字节数 —— **不记目录路径**（路径里有用户名与机器名）。
 */
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
        val lines = buffer.snapshot()
        val written = DiagnosticsReport.write(directory, lines, clock(), zone, appVersion, device)
        return when (written) {
            is DiagnosticsReport.WriteResult.Written -> {
                logger.i(LogTag.UI, EXPORTED_PREFIX + "entries=" + written.entries + ":bytes=" + written.bytes)
                DiagnosticsExportOutcome.Exported(written.path, written.bytes, written.entries)
            }
            is DiagnosticsReport.WriteResult.Failed -> {
                logger.w(LogTag.UI, FAILED_PREFIX + written.reason, null)
                DiagnosticsExportOutcome.Failed(written.reason, written.causeMessage)
            }
        }
    }

    private companion object {
        const val EXPORTED_PREFIX = "diagnostics-exported:"
        const val FAILED_PREFIX = "diagnostics-export-failed:"
    }
}
