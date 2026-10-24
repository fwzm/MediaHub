package com.mediahub.core.common.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-52 地址闸门的单测：缺省仍是「只绑回环」，开关只为「一台机器的地址」打开。
 *
 * 与 Windows W6-51 的 SourceSyncExplicitHostTests 同形：码只有 not-loopback 一个，
 * 差异全部落在 detail 句子里，所以这里的断言钉的是「拒绝句必须说出是哪一类拒绝」。
 *
 * 一条自我约束：本类**不 bind、不 dial 任何非回环地址**——那会在 Windows 上弹防火墙确认框，
 * 违背「自动化测试不得干扰使用者」的纪律（见 docs 第 28 节）。跨机真实两机证据因此仍是 NOT_RUN，
 * 这里只钉「建 socket 之前就拒绝」这件事。
 */
class SourceSyncHostAddressTest {

    private fun accepted(host: String?, allow: Boolean): SourceSyncHostAddress.Resolution.Accepted {
        val resolution = SourceSyncHostAddress.resolve(host, allow)
        if (resolution is SourceSyncHostAddress.Resolution.Failed) {
            throw AssertionError("「" + host + "」本应被接受，实际被拒：" + resolution.detail)
        }
        return resolution as SourceSyncHostAddress.Resolution.Accepted
    }

    private fun refused(host: String?, allow: Boolean): SourceSyncHostAddress.Resolution.Failed {
        val resolution = SourceSyncHostAddress.resolve(host, allow)
        if (resolution is SourceSyncHostAddress.Resolution.Accepted) {
            throw AssertionError("「" + host + "」本应被拒，实际接受为 " + resolution.address)
        }
        return resolution as SourceSyncHostAddress.Resolution.Failed
    }

    // ---------------------------------------------------------------- 回环：开关不改变既有行为

    @Test
    fun blank_and_null_mean_the_default_loopback_address() {
        listOf(null, "", "   ").forEach { host ->
            val resolution = accepted(host, allow = false)
            assertEquals("127.0.0.1", resolution.text)
            assertTrue("缺省地址必须是回环", resolution.address.isLoopbackAddress)
        }
    }

    @Test
    fun every_loopback_spelling_is_accepted_with_and_without_the_switch() {
        val hosts = listOf("127.0.0.1", "  127.0.0.1  ", "::1", "[::1]", "localhost", "LocalHost", "127.0.0.2")
        hosts.forEach { host ->
            listOf(false, true).forEach { allow ->
                val resolution = accepted(host, allow)
                assertTrue("「" + host + "」解析出来必须是回环", resolution.address.isLoopbackAddress)
            }
        }
    }

    @Test
    fun the_switch_does_not_make_a_blank_host_non_loopback() {
        val resolution = accepted(null, allow = true)
        assertEquals("127.0.0.1", resolution.text)
        assertTrue(resolution.address.isLoopbackAddress)
    }

    // ---------------------------------------------------------------- 没有开关：原文那句拒绝

    @Test
    fun a_non_loopback_address_without_the_switch_keeps_the_exact_pre_w6_52_sentence() {
        val failure = refused("192.168.1.5", allow = false)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
        assertEquals("只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 192.168.1.5", failure.detail)
    }

    @Test
    fun a_host_name_without_the_switch_is_refused_by_the_same_sentence() {
        val failure = refused("nas", allow = false)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
        assertEquals("只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 nas", failure.detail)
    }

    // ---------------------------------------------------------------- 有开关：只收单播字面量

    @Test
    fun one_unicast_ipv4_literal_is_accepted_when_the_switch_is_on() {
        listOf("192.168.1.5", "10.0.0.7", "172.16.30.4", " 192.168.1.5 ").forEach { host ->
            val resolution = accepted(host, allow = true)
            assertFalse("被接受的不该是回环", resolution.address.isLoopbackAddress)
            assertEquals(host.trim(), resolution.text)
        }
    }

    @Test
    fun one_unicast_ipv6_literal_is_accepted_when_the_switch_is_on() {
        val resolution = accepted("fd00::1", allow = true)
        assertEquals("fd00::1", resolution.text)
        assertFalse(resolution.address.isLoopbackAddress)
    }

    @Test
    fun wildcard_addresses_are_refused_and_the_sentence_says_wildcard() {
        listOf("0.0.0.0", "::", "0.1.2.3").forEach { host ->
            val failure = refused(host, allow = true)
            assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
            assertTrue(
                "「" + host + "」的拒绝句必须点出「不是一台机器的地址」：" + failure.detail,
                failure.detail.contains("不是一个具体某台机器的地址"),
            )
            assertTrue(failure.detail.contains("通配"))
        }
    }

    @Test
    fun broadcast_multicast_and_reserved_addresses_share_the_same_refusal_sentence() {
        listOf("255.255.255.255", "255.255.255.254", "239.1.2.3", "224.0.0.1", "240.0.0.1", "ff02::1")
            .forEach { host ->
                val failure = refused(host, allow = true)
                assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
                assertTrue(
                    "「" + host + "」必须与通配地址同一句拒绝：" + failure.detail,
                    failure.detail.contains("通配 / 广播 / 组播 / 保留地址"),
                )
            }
    }

    @Test
    fun host_names_are_refused_because_the_transfer_path_never_resolves_them() {
        listOf("mediahub-lan.local", "nas", "example.com", "192.168.1").forEach { host ->
            val failure = refused(host, allow = true)
            assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
            assertTrue(
                "「" + host + "」的拒绝句必须点出「不是 IP 字面量」：" + failure.detail,
                failure.detail.contains("不是 IP 字面量"),
            )
        }
    }

    @Test
    fun an_over_long_address_is_refused_by_length_before_anything_is_parsed() {
        val failure = refused("1".repeat(257), allow = true)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.code)
        assertTrue(failure.detail.contains("最多 256"))
    }

    // ---------------------------------------------------------------- 监听端：拒绝发生在 socket 之前

    @Test
    fun the_listener_refuses_a_wildcard_even_when_the_switch_is_on() {
        // 不 bind：拒绝必须发生在创建 socket 之前，否则这里就会弹出防火墙确认框。
        val start = SourceSyncTransferListener.open("0.0.0.0", 0, allowExplicitHost = true)
        if (start is SourceSyncListenerStart.Started) {
            start.listener.close()
            throw AssertionError("通配地址本应在建 socket 之前被拒绝")
        }
        assertEquals(
            SourceSyncTransferReasons.NOT_LOOPBACK,
            (start as SourceSyncListenerStart.Failed).code,
        )
    }

    @Test
    fun the_listener_refuses_a_unicast_address_when_the_switch_is_off() {
        val start = SourceSyncTransferListener.open("192.168.1.5", 0)
        if (start is SourceSyncListenerStart.Started) {
            start.listener.close()
            throw AssertionError("没开开关时非回环地址本应被拒绝")
        }
        assertEquals(
            SourceSyncTransferReasons.NOT_LOOPBACK,
            (start as SourceSyncListenerStart.Failed).code,
        )
    }

    @Test
    fun the_switch_is_harmless_when_the_address_is_still_loopback() {
        val start = SourceSyncTransferListener.open("127.0.0.1", 0, allowExplicitHost = true)
        if (start is SourceSyncListenerStart.Failed) {
            throw AssertionError("回环地址开着开关也必须能绑：" + start.code + " / " + start.detail)
        }
        val listener = (start as SourceSyncListenerStart.Started).listener
        try {
            assertEquals("127.0.0.1", listener.address)
            assertTrue("系统必须分配一个真实端口", listener.port > 0)
            assertFalse("刚打开时不该是已关闭", listener.isClosed)
        } finally {
            listener.close()
        }
    }
}
