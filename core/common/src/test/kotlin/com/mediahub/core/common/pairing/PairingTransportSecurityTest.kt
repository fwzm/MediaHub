package com.mediahub.core.common.pairing

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-J 安全回归：配对传输客户端的拨号面与帧读取面。
 *
 * 每条"恶意对端"用例都成对给出一个正向对照；测试替身只绑 127.0.0.1:0，
 * 每个用例结束都 close()（关监听 + 在途 socket + join 工线程），不留后台线程。
 */
class PairingTransportSecurityTest {

    private fun reasonOf(result: PairingFrameResult): String = when (result) {
        is PairingFrameResult.Ok -> "ok"
        is PairingFrameResult.Failed -> result.reason
    }

    private fun client(connectTimeoutMs: Int = 1_000, readTimeoutMs: Int = 1_000): PairingTransportClient =
        PairingTransportClient(connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)

    private fun closedPort(): Int {
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        return port
    }

    @Test
    fun only_loopback_literals_reach_the_socket() {
        val host = FakeWindowsPairingHost(label = "loopback-only") { socket ->
            writeFrame(socket, "ok".toByteArray())
        }
        try {
            assertEquals("ok", reasonOf(client().exchange("127.0.0.1", host.port)))
            assertEquals(1, host.acceptedCount)
            for (remoteHost in listOf("192.168.1.20", "10.0.0.5", "0.0.0.0", "example.com", "nonexistent.invalid")) {
                assertEquals(remoteHost, PairingClientReasons.NON_LOOPBACK_PEER, reasonOf(client().exchange(remoteHost, host.port)))
            }
            assertEquals(1, host.acceptedCount)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun ipv6_loopback_literal_is_not_rejected_as_remote() {
        val host = FakeWindowsPairingHost(label = "ipv6") { socket ->
            writeFrame(socket, "ok".toByteArray())
        }
        try {
            val reason = reasonOf(client(connectTimeoutMs = 500, readTimeoutMs = 500).exchange("::1", host.port))
            assertNotEquals(PairingClientReasons.NON_LOOPBACK_PEER, reason)
        } finally {
            host.close()
        }
    }

    @Test
    fun zero_length_header_is_payload_invalid() {
        val host = FakeWindowsPairingHost(label = "zero") { socket -> writeRaw(socket, byteArrayOf(0, 0, 0, 0)) }
        try {
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reasonOf(client().exchange("127.0.0.1", host.port)))
        } finally {
            host.close()
        }
    }

    @Test
    fun negative_length_header_is_payload_invalid() {
        val host = FakeWindowsPairingHost(label = "negative") { socket ->
            writeRaw(socket, byteArrayOf(-1, -1, -1, -1))
        }
        try {
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reasonOf(client().exchange("127.0.0.1", host.port)))
        } finally {
            host.close()
        }
    }

    @Test
    fun overflowing_length_header_is_payload_invalid() {
        val host = FakeWindowsPairingHost(label = "overflow") { socket ->
            writeRaw(socket, byteArrayOf(0x80.toByte(), 0, 0, 0))
        }
        try {
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reasonOf(client().exchange("127.0.0.1", host.port)))
        } finally {
            host.close()
        }
    }

    @Test
    fun oversized_declaration_is_rejected_without_waiting_for_the_body() {
        val host = FakeWindowsPairingHost(label = "oversized") { socket ->
            // 只写帧头，绝不写体：客户端若去等 2GB 会一直挂到超时。
            writeRaw(socket, byteArrayOf(0x7F, -1, -1, -1))
        }
        try {
            val startedAt = System.nanoTime()
            val reason = reasonOf(client(readTimeoutMs = 2_000).exchange("127.0.0.1", host.port))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reason)
            assertTrue("耗时 " + elapsedMs + "ms，说明在等帧体而不是直接拒绝", elapsedMs < 1_500)
        } finally {
            host.close()
        }
    }

    @Test
    fun declaration_beyond_response_limit_is_rejected_while_fitting_one_is_accepted() {
        val body = ByteArray(100) { (it * 3).toByte() }

        val strictHost = FakeWindowsPairingHost(label = "strict") { socket -> writeFrame(socket, body) }
        try {
            assertEquals(
                PairingTransportReasons.PAYLOAD_INVALID,
                reasonOf(client().exchange("127.0.0.1", strictHost.port, responseLimit = 64)),
            )
        } finally {
            strictHost.close()
        }

        val relaxedHost = FakeWindowsPairingHost(label = "relaxed") { socket -> writeFrame(socket, body) }
        try {
            val result = client().exchange("127.0.0.1", relaxedHost.port, responseLimit = 128)
            assertTrue(result is PairingFrameResult.Ok)
            assertArrayEquals(body, (result as PairingFrameResult.Ok).payload)
        } finally {
            relaxedHost.close()
        }
    }

    @Test
    fun response_exactly_at_the_limit_is_accepted() {
        val body = ByteArray(64) { it.toByte() }
        val host = FakeWindowsPairingHost(label = "exact") { socket -> writeFrame(socket, body) }
        try {
            val result = client().exchange("127.0.0.1", host.port, responseLimit = body.size)
            assertTrue(result is PairingFrameResult.Ok)
            assertArrayEquals(body, (result as PairingFrameResult.Ok).payload)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun half_frame_then_close_is_payload_invalid_and_the_socket_is_released() {
        val host = FakeWindowsPairingHost(label = "half-frame") { socket ->
            writeRaw(socket, byteArrayOf(0, 0, 0, 16) + byteArrayOf(1, 2, 3, 4))
            // 只发半帧还不够：必须半关闭写端，否则对端"沉默但不关连接"是超时语义（peer-timeout）。
            socket.shutdownOutput()
            awaitEndOfStream(socket, 2_000)
        }
        try {
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reasonOf(client(readTimeoutMs = 2_000).exchange("127.0.0.1", host.port)))
            assertTrue("客户端在失败路径上也要关 socket", host.awaitPeerClosed(2_000))
        } finally {
            host.close()
        }
    }

    @Test
    fun header_only_then_close_is_payload_invalid() {
        val host = FakeWindowsPairingHost(label = "header-only") { socket ->
            writeRaw(socket, byteArrayOf(0, 0, 0, 16))
            socket.shutdownOutput()
            awaitEndOfStream(socket, 2_000)
        }
        try {
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reasonOf(client(readTimeoutMs = 2_000).exchange("127.0.0.1", host.port)))
            assertTrue(host.awaitPeerClosed(2_000))
        } finally {
            host.close()
        }
    }

    @Test
    fun silent_peer_times_out_and_the_socket_is_released() {
        val host = FakeWindowsPairingHost(label = "silent") { socket ->
            Thread.sleep(800)
            awaitEndOfStream(socket, 2_000)
        }
        try {
            val startedAt = System.nanoTime()
            val reason = reasonOf(client(readTimeoutMs = 150).exchange("127.0.0.1", host.port))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(PairingTransportReasons.PEER_TIMEOUT, reason)
            assertTrue("耗时 " + elapsedMs + "ms，应接近 readTimeout", elapsedMs < 1_500)
            assertTrue(host.awaitPeerClosed(2_000))
        } finally {
            host.close()
        }
    }

    @Test
    fun closed_listener_reports_listener_failed() {
        val port = closedPort()
        val result = client(connectTimeoutMs = 500).exchange("127.0.0.1", port)
        assertEquals(PairingTransportReasons.LISTENER_FAILED, reasonOf(result))
        val failure = result as PairingFrameResult.Failed
        assertTrue(failure.message != null && failure.message!!.isNotBlank())
    }

    @Test
    fun oversized_request_is_rejected_before_dialing() {
        val host = FakeWindowsPairingHost(label = "oversized-request") { socket ->
            writeFrame(socket, "ok".toByteArray())
        }
        try {
            val reason = reasonOf(client().exchange("127.0.0.1", host.port, request = ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES + 1)))
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, reason)
            assertEquals(0, host.acceptedCount)
            val emptyReason = reasonOf(client().exchange("127.0.0.1", host.port, request = ByteArray(0)))
            assertEquals(PairingTransportReasons.PAYLOAD_INVALID, emptyReason)
            assertEquals(0, host.acceptedCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun valid_request_is_written_before_the_response_is_read() {
        val request = "request-frame".toByteArray()
        val host = FakeWindowsPairingHost(label = "echo") { socket ->
            val received = readFrame(socket) ?: ByteArray(0)
            writeFrame(socket, received)
        }
        try {
            val result = client().exchange("127.0.0.1", host.port, request = request)
            assertTrue(result is PairingFrameResult.Ok)
            assertArrayEquals(request, (result as PairingFrameResult.Ok).payload)
            assertArrayEquals(request, host.requestPayload)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }
}
