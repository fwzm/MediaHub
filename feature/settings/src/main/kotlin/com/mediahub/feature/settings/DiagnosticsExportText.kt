package com.mediahub.feature.settings

/**
 * 「导出诊断日志」的对外措辞（W6-110，唯一一处）。
 *
 * 界面与状态都读这里，避免按钮文案、结果句、失败句各写一遍；
 * 加一个状态时只改这一个 when，编译期就会提醒还有哪没给话。
 */
object DiagnosticsExportText {

    /** 按钮文案。 */
    const val BUTTON: String = "导出诊断日志"

    /** 正在导出时的那一行。 */
    const val EXPORTING: String = "正在导出诊断日志…"

    /** 成功句：条目数、字节数与落盘路径。 */
    fun exported(entries: Int, bytes: Int, path: String): String =
        "已导出 " + entries + " 条日志（" + bytes + " 字节）：" + path

    /** 失败句：稳定原因码 +（可选的）净化后原因。 */
    fun failed(reason: String, causeMessage: String?): String =
        "导出失败：" + reason + if (causeMessage.isNullOrBlank()) "" else "（原因：" + causeMessage + "）"

    /** 状态对应的一行话；Idle（还没点过）不给话，界面据此不显示结果行。 */
    fun line(state: DiagnosticsExportState): String? = when (state) {
        DiagnosticsExportState.Idle -> null
        DiagnosticsExportState.Exporting -> EXPORTING
        is DiagnosticsExportState.Exported -> exported(state.entries, state.bytes, state.path)
        is DiagnosticsExportState.Failed -> failed(state.reason, state.causeMessage)
    }
}
