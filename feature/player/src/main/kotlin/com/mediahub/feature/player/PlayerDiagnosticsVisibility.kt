package com.mediahub.feature.player

/**
 * 播放器 OSD 诊断行可见性（审计 P5）。
 *
 * 诊断行是开发者向信息（引擎/协议/HTTP/首包/缓冲），默认不出现在画面上；
 * 只有用户在设置里打开「播放器显示媒体信息浮层」[showPlayerInfoOverlay]，
 * 且引擎确实报告了诊断 [hasDiagnostics] 时才显示。
 *
 * 纯函数，便于单测；UI 只消费结论，不自行拼条件。
 */
internal object PlayerDiagnosticsVisibility {

    fun shouldShow(showPlayerInfoOverlay: Boolean, hasDiagnostics: Boolean): Boolean =
        showPlayerInfoOverlay && hasDiagnostics
}
