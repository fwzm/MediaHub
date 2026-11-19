package com.mediahub.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 P5：开发者向诊断行默认不出现在播放 OSD 上。
 * 这里只钉住"设置开关 + 引擎是否真的报了诊断"这两个输入，UI 只消费结论。
 */
class PlayerDiagnosticsVisibilityTest {

    @Test
    fun defaultPreferenceHidesDiagnosticsEvenWhenEngineReportedThem() {
        assertFalse(PlayerDiagnosticsVisibility.shouldShow(showPlayerInfoOverlay = false, hasDiagnostics = true))
    }

    @Test
    fun optInShowsDiagnosticsWhenEngineReportedThem() {
        assertTrue(PlayerDiagnosticsVisibility.shouldShow(showPlayerInfoOverlay = true, hasDiagnostics = true))
    }

    @Test
    fun optInWithoutEngineDiagnosticsStaysHidden() {
        assertFalse(PlayerDiagnosticsVisibility.shouldShow(showPlayerInfoOverlay = true, hasDiagnostics = false))
    }

    @Test
    fun turningThePreferenceOffHidesAlreadyReportedDiagnostics() {
        assertTrue(PlayerDiagnosticsVisibility.shouldShow(showPlayerInfoOverlay = true, hasDiagnostics = true))
        assertFalse(PlayerDiagnosticsVisibility.shouldShow(showPlayerInfoOverlay = false, hasDiagnostics = true))
    }
}
