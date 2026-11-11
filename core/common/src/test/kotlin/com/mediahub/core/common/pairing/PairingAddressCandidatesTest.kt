package com.mediahub.core.common.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选地址排序与拒绝原因（纯计算，不枚举真实网卡）。 */
class PairingAddressCandidatesTest {

    @Test
    fun wired_candidates_sort_before_wireless_and_other() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Tunnel", PairingInterfaceKind.TUNNEL, true, listOf("192.0.0.9")),
                PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5")),
                PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
            )
        )
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), report.candidates.map { it.address })
        assertEquals(listOf(0, 1), report.candidates.map { it.priority })
        assertEquals(PairingAddressReasons.WIRED, report.candidates[0].reason)
        assertEquals(PairingAddressReasons.WIRELESS, report.candidates[1].reason)
        assertEquals("192.168.1.20", report.primary?.address)
        assertEquals(1, report.rejected.size)
        assertEquals(PairingAddressReasons.OTHER, report.rejected[0].reason)
    }

    @Test
    fun same_priority_sorts_by_address_bytes_not_lexicographically() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Ethernet 2", PairingInterfaceKind.WIRED, true, listOf("10.0.0.11")),
                PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("10.0.0.5")),
            )
        )
        assertEquals(listOf("10.0.0.5", "10.0.0.11"), report.candidates.map { it.address })
    }

    @Test
    fun loopback_and_tunnel_interfaces_reject_every_address() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Loopback Pseudo-Interface 1", PairingInterfaceKind.LOOPBACK, true, listOf("127.0.0.1")),
                PairingAddressInput("Tunnel", PairingInterfaceKind.TUNNEL, true, listOf("192.0.0.2")),
            )
        )
        assertTrue(report.isEmpty)
        assertEquals(2, report.rejected.size)
        assertEquals(PairingAddressReasons.LOOPBACK, report.rejected[0].reason)
        assertEquals(2, report.rejected[0].priority)
        assertEquals(PairingAddressReasons.OTHER, report.rejected[1].reason)
        assertEquals(2, report.rejected[1].priority)
        assertEquals(PairingAddressReasons.NO_USABLE_ADDRESS, report.reason)
        assertNull(report.primary)
    }

    @Test
    fun non_ipv4_is_rejected_before_the_interface_down_check() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, false, listOf("fe80::1", "172.16.0.9")),
            )
        )
        assertEquals(2, report.rejected.size)
        assertEquals("fe80::1", report.rejected[0].address)
        assertEquals(PairingAddressReasons.NOT_IPV4, report.rejected[0].reason)
        assertEquals("172.16.0.9", report.rejected[1].address)
        assertEquals(PairingAddressReasons.INTERFACE_DOWN, report.rejected[1].reason)
    }

    @Test
    fun interface_down_and_apipa_and_loopback_are_rejected_with_their_reasons() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, false, listOf("172.16.0.9")),
                PairingAddressInput("Ethernet 2", PairingInterfaceKind.WIRED, true, listOf("169.254.10.10")),
                PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("127.0.0.5")),
            )
        )
        assertTrue(report.isEmpty)
        assertEquals(3, report.rejected.size)
        assertEquals(PairingAddressReasons.INTERFACE_DOWN, report.rejected[0].reason)
        assertEquals(PairingAddressReasons.APIPA, report.rejected[1].reason)
        assertEquals(0, report.rejected[1].priority)
        assertEquals(PairingAddressReasons.LOOPBACK, report.rejected[2].reason)
        assertEquals(1, report.rejected[2].priority)
    }

    @Test
    fun duplicate_address_is_dropped_without_becoming_a_rejection() {
        val report = PairingAddressCandidates.describe(
            listOf(
                PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20", "192.168.1.20")),
                PairingAddressInput("Ethernet 2", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
            )
        )
        assertEquals(1, report.count)
        assertEquals("Ethernet", report.candidates[0].interfaceName)
        assertEquals(0, report.rejected.size)
        assertNull(report.reason)
    }

    @Test
    fun empty_input_and_failed_enumeration_use_distinct_reasons() {
        val empty = PairingAddressCandidates.describe(emptyList())
        assertTrue(empty.isEmpty)
        assertEquals(PairingAddressReasons.NO_USABLE_ADDRESS, empty.reason)
        assertEquals(0, empty.count)

        val failed = PairingAddressCandidates.failed()
        assertEquals(PairingAddressReasons.ENUMERATION_FAILED, failed.reason)
        assertTrue(failed.isEmpty)
        assertEquals(0, failed.rejected.size)
    }

    @Test
    fun ipv4_parser_is_strict_and_avoids_dns() {
        assertArrayEquals(intArrayOf(192, 168, 1, 20), PairingAddressCandidates.parseIpv4("192.168.1.20"))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), PairingAddressCandidates.parseIpv4("0.0.0.0"))
        assertArrayEquals(intArrayOf(255, 255, 255, 255), PairingAddressCandidates.parseIpv4("255.255.255.255"))
        for (text in listOf("", "192.168.1", "192.168.1.20.5", "192.168.1.256", "192.168..20", "a.b.c.d", "192.168.1.20:8942", " 192.168.1.20", "fe80::1")) {
            assertNull(text, PairingAddressCandidates.parseIpv4(text))
        }
    }

    @Test
    fun candidate_url_uses_the_pairing_path_and_given_port() {
        val candidate = PairingAddressCandidate("10.0.0.5", "Wi-Fi", 1, PairingAddressReasons.WIRELESS)
        assertEquals("https://10.0.0.5:8942/pairing", candidate.toUrl(8942))
        assertFalse(candidate.toUrl(8942).contains("http://"))
        assertNotNull(PairingAddressCandidates.describe(
            listOf(PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5"))),
        ).primary)
    }
}
