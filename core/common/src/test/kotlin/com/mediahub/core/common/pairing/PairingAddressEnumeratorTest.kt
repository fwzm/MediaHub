package com.mediahub.core.common.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-60：真实网卡 → [PairingAddressInput] 的适配层证据（纯 JVM，不读真网卡）。
 *
 * 这一层钉三件事：
 * 1. 接口分类的顺序（回环 > 隧道 > 无线 > 有线）逐条可断言，不需要真机；
 * 2. 枚举失败一律落进既有码 address-enumeration-failed，不自造 Android 专用码；
 * 3. 适配层只是把网卡数据搬进 [PairingAddressCandidates]，排序/拒绝原因仍由那一层决定
 *    （所以这里断言的是「搬得对不对」，不是重测排序）。
 */
class PairingAddressEnumeratorTest {

    /** 假网卡来源：回给定输入，或按需抛异常。 */
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

    @Test
    fun loopbackWinsBeforeEveryOtherFlag() {
        // 回环标志最先判：哪怕它同时是虚拟接口、名字像隧道，也必须落成 LOOPBACK。
        assertEquals(
            PairingInterfaceKind.LOOPBACK,
            PairingInterfaceKinds.of("lo", loopback = true, virtual = true, pointToPoint = true),
        )
        assertEquals(
            PairingInterfaceKind.LOOPBACK,
            PairingInterfaceKinds.of("tun0", loopback = true, virtual = false, pointToPoint = false),
        )
    }

    @Test
    fun virtualPointToPointAndTunnelPrefixesAllBecomeTunnel() {
        assertEquals(
            PairingInterfaceKind.TUNNEL,
            PairingInterfaceKinds.of("eth0", loopback = false, virtual = true, pointToPoint = false),
        )
        assertEquals(
            PairingInterfaceKind.TUNNEL,
            PairingInterfaceKinds.of("rmnet0", loopback = false, virtual = false, pointToPoint = true),
        )
        for (name in listOf("tun0", "tap0", "ppp0", "ipsec0", "pptp0", "l2tp0", "wg0")) {
            assertEquals(
                name,
                PairingInterfaceKind.TUNNEL,
                PairingInterfaceKinds.of(name, loopback = false, virtual = false, pointToPoint = false),
            )
        }
    }

    @Test
    fun wirelessPrefixesBecomeWirelessInAnyCase() {
        for (name in listOf("wlan0", "wl0", "wifi0", "swlan0", "WLAN0", "Wlan1")) {
            assertEquals(
                name,
                PairingInterfaceKind.WIRELESS,
                PairingInterfaceKinds.of(name, loopback = false, virtual = false, pointToPoint = false),
            )
        }
    }

    @Test
    fun everythingElseIsWiredIncludingAnEmptyName() {
        for (name in listOf("eth0", "Ethernet", "en0", "rmnet_data0", "")) {
            assertEquals(
                name,
                PairingInterfaceKind.WIRED,
                PairingInterfaceKinds.of(name, loopback = false, virtual = false, pointToPoint = false),
            )
        }
    }

    @Test
    fun aReadFailureBecomesTheExistingEnumerationFailedCodeAndNeverThrows() {
        val source = FakeSource(failure = IllegalStateException("NetworkInterface.getNetworkInterfaces threw"))

        val report = PairingAddressEnumerator(source).enumerate()

        assertTrue(report.isEmpty)
        assertEquals(0, report.rejected.size)
        assertEquals(PairingAddressReasons.ENUMERATION_FAILED, report.reason)
        assertEquals(1, source.reads)
    }

    @Test
    fun anEmptyReadIsNoUsableAddressNotAnEnumerationFailure() {
        // 读到空表 = 读成功了但没有可用地址，与「读不到」是两个不同的码，别混。
        val report = PairingAddressEnumerator(FakeSource()).enumerate()

        assertTrue(report.isEmpty)
        assertEquals(PairingAddressReasons.NO_USABLE_ADDRESS, report.reason)
    }

    @Test
    fun theAdapterPassesInterfacesThroughAndLetsTheCandidateLayerSortThem() {
        val report = PairingAddressEnumerator(
            FakeSource(
                listOf(
                    PairingAddressInput("wlan0", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5")),
                    PairingAddressInput("eth0", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20", "fe80::1")),
                    PairingAddressInput("lo", PairingInterfaceKind.LOOPBACK, true, listOf("127.0.0.1")),
                ),
            )
        ).enumerate()

        assertEquals(listOf("192.168.1.20", "10.0.0.5"), report.candidates.map { it.address })
        assertEquals(listOf("eth0", "wlan0"), report.candidates.map { it.interfaceName })
        assertEquals(PairingAddressReasons.WIRED, report.candidates[0].reason)
        assertEquals(PairingAddressReasons.WIRELESS, report.candidates[1].reason)
        // 非 IPv4 与回环接口的地址都进 rejected，原样保留原因。
        assertEquals(listOf("fe80::1", "127.0.0.1"), report.rejected.map { it.address })
        assertEquals(PairingAddressReasons.NOT_IPV4, report.rejected[0].reason)
        assertEquals(PairingAddressReasons.LOOPBACK, report.rejected[1].reason)
        assertEquals("192.168.1.20", report.primary?.address)
        assertNull(report.reason)
    }

    @Test
    fun theProductionSourceIsTheDefaultSoTheCallerCannotForgetIt() {
        // 默认构造就是生产实现：调用方不需要知道 java.net 的存在。
        val enumerator = PairingAddressEnumerator()
        val report = enumerator.enumerate()

        // 不假设本机一定有网卡（CI runner 可能只有回环），只要求「从不抛出且码只在这两个里」。
        assertTrue(
            report.reason == null ||
                report.reason == PairingAddressReasons.NO_USABLE_ADDRESS ||
                report.reason == PairingAddressReasons.ENUMERATION_FAILED,
        )
    }
}
