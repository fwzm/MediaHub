package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingAddressCandidates
import com.mediahub.core.common.pairing.PairingAddressInput
import com.mediahub.core.common.pairing.PairingAddressReasons
import com.mediahub.core.common.pairing.PairingInterfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-60：「本机可被连的地址」区块的文案证据（纯 JVM）。
 *
 * 这一层钉的是**给人看的那几句话**：有候选时说出几个、首选是谁；没候选时说清是「没地址」还是
 * 「读不到」；地址行不含端口、不含 URL（端口由发起同步的那一页决定，这一块不替它编）。
 */
class PairingAddressSummaryTest {

    private fun report(vararg inputs: PairingAddressInput) = PairingAddressCandidates.describe(inputs.toList())

    private val wiredAndWireless = report(
        PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
        PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5")),
    )

    @Test
    fun theHeadlineSaysHowManyAndWhichIsPrimary() {
        val line = PairingAddressSummary.lineOf(wiredAndWireless)

        assertTrue(line.contains("2 个可用地址"))
        assertTrue(line.contains("192.168.1.20"))
        assertTrue(line.contains("Ethernet"))
        assertTrue(line.contains("有线"))
        assertNull(PairingAddressSummary.codeOf(wiredAndWireless))
    }

    @Test
    fun eachCandidateGetsOneLineWithItsInterfaceAndKind() {
        val lines = PairingAddressSummary.linesOf(wiredAndWireless)

        assertEquals(2, lines.size)
        assertEquals("192.168.1.20（Ethernet，有线）", lines[0])
        assertEquals("10.0.0.5（Wi-Fi，无线）", lines[1])
    }

    @Test
    fun theLinesCarryNeitherAPortNorAUrl() {
        for (line in PairingAddressSummary.linesOf(wiredAndWireless)) {
            assertFalse(line, line.contains("https://"))
            assertFalse(line, line.contains(":"))
        }
    }

    @Test
    fun noUsableAddressAndAnEnumerationFailureReadDifferently() {
        val empty = PairingAddressCandidates.describe(emptyList())
        val failed = PairingAddressCandidates.failed()

        assertTrue(PairingAddressSummary.lineOf(empty).contains(PairingAddressReasons.NO_USABLE_ADDRESS))
        assertTrue(PairingAddressSummary.lineOf(failed).contains(PairingAddressReasons.ENUMERATION_FAILED))
        assertTrue(PairingAddressSummary.lineOf(empty) != PairingAddressSummary.lineOf(failed))
        assertEquals(PairingAddressReasons.NO_USABLE_ADDRESS, PairingAddressSummary.codeOf(empty))
        assertEquals(PairingAddressReasons.ENUMERATION_FAILED, PairingAddressSummary.codeOf(failed))
        assertTrue(PairingAddressSummary.linesOf(empty).isEmpty())
        assertTrue(PairingAddressSummary.linesOf(failed).isEmpty())
    }

    @Test
    fun theBlockTextSaysItIsDisplayOnlyAndNeverSaysListen() {
        assertTrue(PairingAddressSummary.DISPLAY_ONLY_HINT.contains("只做展示"))
        assertTrue(PairingAddressSummary.DISPLAY_ONLY_HINT.contains("不监听"))
        // 这一块的标题与按钮文案都不能出现「监听」这种动作词，否则用户会以为这里能开服务。
        assertFalse(PairingAddressSummary.TITLE.contains("监听"))
        assertFalse(PairingAddressSummary.REFRESH.contains("监听"))
    }
}
