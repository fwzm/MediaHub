package com.mediahub.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AFR 决策纯逻辑（M2 / ADR-044）。仅证明判定表，不代表真机切换结果（设备验证 NOT_RUN）。
 */
class FrameRateMatcherTest {

    @Test
    fun `api below 30 never switches`() {
        val decision = FrameRateMatcher.decide(23.976f, listOf(24f, 60f), apiLevel = 29)
        assertFalse(decision.shouldSwitch)
        assertTrue(decision.reason.contains("API"))
    }

    @Test
    fun `unknown source frame rate never switches`() {
        assertFalse(FrameRateMatcher.decide(null, listOf(60f), 34).shouldSwitch)
        assertFalse(FrameRateMatcher.decide(0f, listOf(60f), 34).shouldSwitch)
        assertFalse(FrameRateMatcher.decide(-1f, listOf(60f), 34).shouldSwitch)
    }

    @Test
    fun `missing display evidence never switches`() {
        val decision = FrameRateMatcher.decide(24f, emptyList(), 34)
        assertFalse(decision.shouldSwitch)
        assertTrue(decision.reason.contains("未报告"))
    }

    @Test
    fun `exact multiple match switches to the smallest rate`() {
        val decision = FrameRateMatcher.decide(24f, listOf(60f, 48f, 120f), 34)
        assertTrue(decision.shouldSwitch)
        assertEquals(48f, decision.targetRateHz, 0.001f)
        assertEquals(2, decision.multiplier)
    }

    @Test
    fun `ntsc rate tolerates the 24hz panel rounding`() {
        val decision = FrameRateMatcher.decide(23.976f, listOf(60f, 24f), 34)
        assertTrue(decision.shouldSwitch)
        assertEquals(24f, decision.targetRateHz, 0.001f)
        assertEquals(1, decision.multiplier)
    }

    @Test
    fun `non integer relationship never switches`() {
        // 60 / 23.976 = 2.5025 → 非整数倍，且 24Hz 不在列表 → 不切换
        val decision = FrameRateMatcher.decide(23.976f, listOf(60f), 34)
        assertFalse(decision.shouldSwitch)
        assertTrue(decision.reason.contains("无整数倍匹配"))
    }

    @Test
    fun `all common source rates have a documented decision path`() {
        val panel = listOf(24f, 25f, 30f, 50f, 60f, 120f)
        FrameRateMatcher.COMMON_SOURCE_RATES.forEach { fps ->
            val decision = FrameRateMatcher.decide(fps, panel, 34)
            assertTrue("no decision for " + fps, decision.reason.isNotEmpty())
        }
    }
}
