package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingAddressEnumerator
import com.mediahub.core.common.pairing.PairingAddressInput
import com.mediahub.core.common.pairing.PairingAddressReasons
import com.mediahub.core.common.pairing.PairingInterfaceKind
import com.mediahub.core.common.pairing.PairingNetworkInterfaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-60：配对页「本机地址」这一段的控制器证据（纯 JVM）。
 *
 * 与 [PairingLinkControllerTest] 分工：那边钉「贴链接 → 核对 → 拉邀请」，这边钉地址区块——
 * 没接出口时不假装读到空、接了就真的读一次、读失败落既有码、读的过程中不会读第二遍。
 * 地址区块与链接状态是两条独立状态流，互不覆盖。
 */
class PairingLinkAddressesTest {

    private class FakeSource(
        private val inputs: List<PairingAddressInput> = emptyList(),
        private val failure: Exception? = null,
    ) : PairingNetworkInterfaceSource {

        var reads: Int = 0

        override fun read(): List<PairingAddressInput> {
            reads += 1
            failure?.let { throw it }
            return inputs
        }
    }

    private fun controller(source: PairingNetworkInterfaceSource?): PairingLinkController =
        PairingLinkController(
            now = { 1_760_000_000_000L },
            addressEnumerator = source?.let { PairingAddressEnumerator(it) },
        )

    @Test
    fun withoutAnEnumeratorTheBlockIsUnavailableAndNothingIsPretended() {
        val controller = controller(null)

        assertEquals(PairingAddressState.Unavailable, controller.addresses.value)
        assertFalse(controller.refreshAddresses())
        assertEquals(PairingAddressState.Unavailable, controller.addresses.value)
    }

    @Test
    fun aRefreshReadsTheInterfacesOnceAndShowsTheCandidates() {
        val source = FakeSource(
            listOf(
                PairingAddressInput("wlan0", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5")),
                PairingAddressInput("eth0", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
            ),
        )
        val controller = controller(source)

        assertEquals(PairingAddressState.NotRead, controller.addresses.value)
        assertTrue(controller.refreshAddresses())

        val state = controller.addresses.value as PairingAddressState.Ready
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), state.report.candidates.map { it.address })
        assertEquals(1, source.reads)
    }

    @Test
    fun aReadFailureLandsOnTheExistingEnumerationFailedCode() {
        val controller = controller(FakeSource(failure = IllegalStateException("no /proc/net/dev")))

        assertTrue(controller.refreshAddresses())

        val state = controller.addresses.value as PairingAddressState.Ready
        assertTrue(state.report.isEmpty)
        assertEquals(PairingAddressReasons.ENUMERATION_FAILED, state.report.reason)
    }

    @Test
    fun anEmptyReadIsNoUsableAddress() {
        val controller = controller(FakeSource())

        assertTrue(controller.refreshAddresses())

        val state = controller.addresses.value as PairingAddressState.Ready
        assertEquals(PairingAddressReasons.NO_USABLE_ADDRESS, state.report.reason)
    }

    @Test
    fun addressesAndTheLinkStateAreTwoIndependentStreams() {
        val controller = controller(
            FakeSource(listOf(PairingAddressInput("eth0", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")))),
        )

        assertTrue(controller.refreshAddresses())
        // 读地址不影响链接那条状态流（还是 Idle），清空链接也不影响地址。
        assertEquals(PairingLinkState.Idle, controller.state.value)
        assertEquals(1, (controller.addresses.value as PairingAddressState.Ready).report.count)

        controller.clear()

        assertEquals(PairingLinkState.Idle, controller.state.value)
        assertEquals(1, (controller.addresses.value as PairingAddressState.Ready).report.count)
    }

    @Test
    fun aSecondRefreshReReadsAndReplacesTheReport() {
        val source = FakeSource(
            listOf(PairingAddressInput("eth0", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20"))),
        )
        val controller = controller(source)

        assertTrue(controller.refreshAddresses())
        assertTrue(controller.refreshAddresses())

        assertEquals(2, source.reads)
        assertEquals("192.168.1.20", (controller.addresses.value as PairingAddressState.Ready).report.primary?.address)
        assertNull(PairingAddressSummary.codeOf((controller.addresses.value as PairingAddressState.Ready).report))
    }
}
