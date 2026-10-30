package com.mediahub.feature.settings

object DiagnosticsExportText {
    const val BUTTON: String = "导出诊断日志"
    const val EXPORTING: String = "正在导出诊断日志…"

    fun exported(entries: Int, bytes: Int, path: String): String =
        "已导出 $entries 条日志（$bytes 字节）：$path"

    fun failed(reason: String, causeMessage: String?): String =
        "导出失败：$reason" + if (causeMessage.isNullOrBlank()) "" else "（原因：$causeMessage）"

    fun line(state: DiagnosticsExportState): String? = when (state) {
        DiagnosticsExportState.Idle -> null
        DiagnosticsExportState.Exporting -> EXPORTING
        is DiagnosticsExportState.Exported -> exported(state.entries, state.bytes, state.path)
        is DiagnosticsExportState.Failed -> failed(state.reason, state.causeMessage)
    }
}
