package com.mediahub.core.common.sync

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-52b 拨号侧的地址闸门：缺省仍是「只连回环」，开关只为「一台机器的地址」打开。
 *
 * 与监听端的 SourceSyncHostAddressTest 同形，但断言的对象是 [SourceSyncTransferClient] 的两个入口
 * （[SourceSyncTransferClient.sendTo] / [SourceSyncTransferClient.receiveFrom]）：判定与诊断句全部来自
 * [SourceSyncHostAddress]，所以这里钉两件事——①开关关着时那句「只允许连回环地址…」逐字不变（动词是「连」，
 * 与监听端的「绑定」区分开）；②开关打开后通配 / 广播 / 组播 / 保留地址与主机名仍然在拨号之前被拒。
 *
 * 一条自我约束：本类**不 bind、不 dial 任何非回环地址**——那会在 Windows 上弹防火墙确认框，
 * 违背「自动化测试不得干扰使用者」的纪律（见 docs 第 28 节）。跨机真实两机证据因此仍是 NOT_RUN，
 * 这里只钉「建 socket 之前就拒绝」这件事。
 */
class SourceSyncTransferClientHostTest {

    private val payload = ByteArray(32) { (it + 1).toByte() }

    private fun refused(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Failed {
        if (result is SourceSyncTransferResult.Ok) {
            throw AssertionError(what + "：本应被拒，实际连上了（frames=" + result.frameCount + "）")
        }
        return result as SourceSyncTransferResult.Failed
    }

    /** 一个本机刚释放过的端口：只用来验证「已经过了地址闸门」，不让它真的连上。 */
    private fun closedLoopbackPort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    // ---------------------------------------------------------------- 开关关着：与 W6-23 起的句子逐字相同

    @Test
    fun sending_without_the_switch_keeps_the_exact_pre_w6_52b_sentence() {
        val result = SourceSyncTransferClient().sendTo("192.168.1.5", 9000, payload)
        val failure = refused(result, "没开开关时非回环地址")
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
        assertEquals("只允许连回环地址（127.0.0.1 / ::1 / localhost），实际 192.168.1.5", failure.message)
    }

    @Test
    fun receiving_without_the_switch_uses_the_same_sentence() {
        val result = SourceSyncTransferClient().receiveFrom("10.0.0.7", 9000)
        val failure = refused(result, "没开开关时非回环地址")
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
        assertEquals("只允许连回环地址（127.0.0.1 / ::1 / localhost），实际 10.0.0.7", failure.message)
    }

    @Test
    fun the_dialling_side_says_connect_and_never_bind() {
        val failure = refused(SourceSyncTransferClient().sendTo("nas", 9000, payload), "主机名")
        val message = checkNotNull(failure.message)
        assertTrue("拨号端必须说「连」：" + message, message.startsWith("只允许连回环地址"))
        assertFalse("拨号端不该出现监听端的「绑定」：" + message, message.contains("绑定"))
    }

    @Test
    fun the_listener_side_keeps_its_bind_wording() {
        // verb 的缺省值只给监听端用：这一句是 W6-52 落盘时的原文，不能被 W6-52b 顺手改掉。
        val resolution = SourceSyncHostAddress.resolve("192.168.1.5", allowExplicitHost = false)
        val failure = resolution as SourceSyncHostAddress.Resolution.Failed
        assertEquals(
            "只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 192.168.1.5",
            failure.detail,
        )
    }

    // ---------------------------------------------------------------- 开关打开：只多接受一台机器的地址

    @Test
    fun a_unicast_literal_is_accepted_by_the_resolver_on_the_dialling_side() {
        val resolution = SourceSyncHostAddress.resolve("192.168.1.5", allowExplicitHost = true, verb = "连")
        val accepted = resolution as SourceSyncHostAddress.Resolution.Accepted
        assertEquals("192.168.1.5", accepted.text)
        assertFalse("一个网段地址不该被当成回环", accepted.address.isLoopbackAddress)
    }

    @Test
    fun wildcard_addresses_are_refused_before_any_socket_with_the_switch_on() {
        val started = System.nanoTime()
        listOf("0.0.0.0", "::").forEach { host ->
            val failure = refused(
                SourceSyncTransferClient().sendTo(host, 9000, payload, allowExplicitHost = true),
                host,
            )
            assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
            assertTrue(
                "拒绝句要说清是「不是一个具体某台机器的地址」：" + failure.message,
                checkNotNull(failure.message).contains("不是一个具体某台机器的地址"),
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("拒绝必须在拨号之前完成，实际耗时 " + elapsedMs + " ms", elapsedMs < 2_000)
    }

    @Test
    fun broadcast_multicast_and_reserved_addresses_are_refused_with_the_switch_on() {
        listOf("255.255.255.255", "239.1.2.3", "224.0.0.1", "240.0.0.1", "ff02::1").forEach { host ->
            val failure = refused(
                SourceSyncTransferClient().sendTo(host, 9000, payload, allowExplicitHost = true),
                host,
            )
            assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
            assertTrue(
                "拒绝句要给出一个单播例子：" + failure.message,
                checkNotNull(failure.message).contains(SourceSyncHostAddress.EXAMPLE_UNICAST),
            )
        }
    }

    @Test
    fun host_names_are_refused_even_with_the_switch_on() {
        listOf("mediahub-lan.local", "nas", "192.168.1").forEach { host ->
            val failure = refused(
                SourceSyncTransferClient().sendTo(host, 9000, payload, allowExplicitHost = true),
                host,
            )
            assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
            assertTrue(
                "本版本传输不做域名解析：" + failure.message,
                checkNotNull(failure.message).contains("不是 IP 字面量"),
            )
        }
    }

    @Test
    fun an_over_long_address_is_refused_by_length_before_anything_is_parsed() {
        val failure = refused(
            SourceSyncTransferClient().sendTo("1".repeat(257), 9000, payload, allowExplicitHost = true),
            "超长地址",
        )
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, failure.reason)
        assertTrue(
            "要先说长度上限：" + failure.message,
            checkNotNull(failure.message).contains("最多 256"),
        )
    }

    // ---------------------------------------------------------------- 回环：开关开着也必须照旧放行

    @Test
    fun a_loopback_target_gets_past_the_gate_and_only_then_fails_to_connect() {
        // 这条证明「开关不会把回环也一起拒掉」：过了闸门才会去连，而那个端口刚被释放 ⇒ 只能是 connect-failed。
        val port = closedLoopbackPort()
        listOf(false, true).forEach { allow ->
            val failure = refused(
                SourceSyncTransferClient().sendTo("127.0.0.1", port, payload, allowExplicitHost = allow),
                "回环地址 allow=" + allow,
            )
            assertNotEquals(
                "回环地址不该被地址闸门拒绝（allow=" + allow + "）",
                SourceSyncTransferReasons.NOT_LOOPBACK,
                failure.reason,
            )
            assertEquals(SourceSyncTransferReasons.CONNECT_FAILED, failure.reason)
        }
    }

    @Test
    fun a_blank_host_still_means_loopback_on_the_dialling_side() {
        val port = closedLoopbackPort()
        val failure = refused(
            SourceSyncTransferClient().receiveFrom("", port, allowExplicitHost = true),
            "空白地址",
        )
        assertEquals(SourceSyncTransferReasons.CONNECT_FAILED, failure.reason)
    }
}
