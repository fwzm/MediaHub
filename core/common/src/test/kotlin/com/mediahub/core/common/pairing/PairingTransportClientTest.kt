package com.mediahub.core.common.pairing

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PairingTransportClient 的行为测试：全部走 127.0.0.1 上的真实 socket，不 mock 网络。
 *
 * 失败路径只断言稳定 reason 码，不用「抛了异常」当通过标准——否则将来码表漂移也测不出来。
 * 每个用例结束都关掉假主机（AutoCloseable），端口与线程不残留。
 */
class PairingTransportClientTest {

    private val now = 1_792_800_000_000L

    @Test
    fun fetchInvitation_returns_the_payload_carried_by_a_windows_style_frame() {
        val text = invitationJson()
        val host = FakeWindowsPairingHost("invite") { socket ->
            writeFrame(socket, text.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val client = PairingTransportClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
            val result = client.fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge(),
                nowEpochMs = now + 1_000L,
            )
            assertTrue("期望成功，实际 " + reasonOf(result), result is PairingInvitationResult.Ok)
            val ok = result as PairingInvitationResult.Ok
            assertEquals("device-windows", ok.payload.deviceId)
            assertEquals("https://127.0.0.1:8942/pairing", ok.payload.ephemeralUrl)
            assertEquals(challenge().base64, ok.payload.challenge.base64)
            assertEquals(fingerprint().hex, ok.payload.publicKeyFingerprint.hex)
            assertEquals(text, ok.json)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun exchange_sends_the_request_frame_before_reading_the_response() {
        val request = "android-request".toByteArray(Charsets.UTF_8)
        val response = byteArrayOf(1, 2, 3, 4)
        val host = FakeWindowsPairingHost("echo") { socket ->
            val received = readFrame(socket)
            if (received != null && received.contentEquals(request)) {
                writeFrame(socket, response)
            } else {
                writeRaw(socket, byteArrayOf(0, 0, 0, 0))
            }
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", host.port, request)
            assertTrue("期望成功，实际 " + reasonOf(result), result is PairingFrameResult.Ok)
            assertArrayEquals(response, (result as PairingFrameResult.Ok).payload)
            assertArrayEquals(request, host.requestPayload)
        } finally {
            host.close()
        }
    }

    @Test
    fun exchange_without_a_request_only_reads_the_remote_frame() {
        val response = byteArrayOf(9, 8, 7)
        val host = FakeWindowsPairingHost("push") { socket ->
            writeFrame(socket, response)
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).exchange(
                host = "127.0.0.1",
                port = host.port,
                request = null,
            )
            assertTrue("期望成功，实际 " + reasonOf(result), result is PairingFrameResult.Ok)
            assertArrayEquals(response, (result as PairingFrameResult.Ok).payload)
            assertNull("只读交换不应写请求帧", host.requestPayload)
        } finally {
            host.close()
        }
    }

    @Test
    fun exchange_accepts_a_payload_of_exactly_the_maximum_size() {
        val big = ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES) { index -> (index % 251).toByte() }
        val host = FakeWindowsPairingHost("big") { socket ->
            writeFrame(socket, big)
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", host.port)
            assertTrue("期望成功，实际 " + reasonOf(result), result is PairingFrameResult.Ok)
            assertArrayEquals(big, (result as PairingFrameResult.Ok).payload)
        } finally {
            host.close()
        }
    }

    @Test
    fun oversize_request_is_rejected_without_dialing() {
        val host = FakeWindowsPairingHost("idle") { socket ->
            awaitEndOfStream(socket)
        }
        try {
            val client = PairingTransportClient(500, 500)
            val result = client.exchange("127.0.0.1", host.port, ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES + 1))
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
            Thread.sleep(150)
            assertEquals(0, host.acceptedCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun empty_request_is_rejected_without_dialing() {
        val host = FakeWindowsPairingHost("idle") { socket ->
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(500, 500).exchange("127.0.0.1", host.port, ByteArray(0))
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
            Thread.sleep(150)
            assertEquals(0, host.acceptedCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun empty_expected_challenge_is_rejected_without_dialing() {
        val host = FakeWindowsPairingHost("idle") { socket ->
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(500, 500).fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = PairingChallenge.EMPTY,
                nowEpochMs = now,
            )
            assertInvitationFailed(PairingReasons.BAD_CHALLENGE, result)
            Thread.sleep(150)
            assertEquals(0, host.acceptedCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun non_loopback_targets_are_rejected_before_dialing() {
        val host = FakeWindowsPairingHost("idle") { socket ->
            awaitEndOfStream(socket)
        }
        try {
            val client = PairingTransportClient(500, 500)
            assertFrameFailed(
                PairingClientReasons.NON_LOOPBACK_PEER,
                client.exchange("192.168.1.20", host.port),
            )
            assertFrameFailed(
                PairingClientReasons.NON_LOOPBACK_PEER,
                client.exchange("example.com", host.port),
            )
            assertFrameFailed(
                PairingClientReasons.NON_LOOPBACK_PEER,
                client.exchange("10.0.0.5", host.port),
            )
            Thread.sleep(150)
            assertEquals(0, host.acceptedCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun refused_connection_maps_to_listener_failed() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = PairingTransportClient(500, 500).exchange("127.0.0.1", port)
        assertFrameFailed(PairingTransportReasons.LISTENER_FAILED, result)
    }

    @Test
    fun read_timeout_maps_to_peer_timeout() {
        val host = FakeWindowsPairingHost("silent") { _ ->
            Thread.sleep(1_500)
        }
        try {
            val result = PairingTransportClient(2_000, 200).exchange("127.0.0.1", host.port)
            assertFrameFailed(PairingTransportReasons.PEER_TIMEOUT, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun zero_length_header_maps_to_payload_invalid() {
        val host = FakeWindowsPairingHost("zero") { socket ->
            writeRaw(socket, byteArrayOf(0, 0, 0, 0))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 1_000).exchange("127.0.0.1", host.port)
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun oversized_declared_length_is_rejected_without_reading_the_body() {
        val host = FakeWindowsPairingHost("over") { socket ->
            writeRaw(socket, byteArrayOf(0, 0, 0x20, 0x01))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 300).exchange("127.0.0.1", host.port)
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun truncated_body_maps_to_payload_invalid() {
        val host = FakeWindowsPairingHost("short") { socket ->
            writeRaw(socket, byteArrayOf(0, 0, 0, 16, 0x6F, 0x6E, 0x6C, 0x79))
        }
        try {
            val result = PairingTransportClient(2_000, 1_000).exchange("127.0.0.1", host.port)
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun peer_closing_before_any_byte_maps_to_payload_invalid() {
        val host = FakeWindowsPairingHost("close-early") { _ ->
        }
        try {
            val result = PairingTransportClient(2_000, 1_000).exchange("127.0.0.1", host.port)
            assertFrameFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun payload_that_is_not_a_pairing_invitation_maps_to_payload_invalid() {
        val host = FakeWindowsPairingHost("garbage") { socket ->
            writeFrame(socket, "not json".toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge(),
                nowEpochMs = now,
            )
            assertInvitationFailed(PairingTransportReasons.PAYLOAD_INVALID, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun expired_invitation_maps_to_lifetime_exceeded() {
        val text = invitationJson(nowEpochMs = now)
        val host = FakeWindowsPairingHost("expired") { socket ->
            writeFrame(socket, text.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge(),
                nowEpochMs = now + PairingPayload.LIFETIME_MILLISECONDS,
            )
            assertInvitationFailed(PairingTransportReasons.LIFETIME_EXCEEDED, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun challenge_mismatch_maps_to_challenge_mismatch() {
        val text = invitationJson(expectedChallenge = challenge(1))
        val host = FakeWindowsPairingHost("challenge") { socket ->
            writeFrame(socket, text.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge(2),
                nowEpochMs = now + 1_000L,
            )
            assertInvitationFailed(PairingReasons.CHALLENGE_MISMATCH, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun fingerprint_mismatch_maps_to_fingerprint_mismatch() {
        val text = invitationJson(expectedFingerprint = fingerprint(0x11))
        val host = FakeWindowsPairingHost("fingerprint") { socket ->
            writeFrame(socket, text.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge(),
                expectedFingerprint = fingerprint(0x33),
                nowEpochMs = now + 1_000L,
            )
            assertInvitationFailed(PairingTransportReasons.FINGERPRINT_MISMATCH, result)
        } finally {
            host.close()
        }
    }

    @Test
    fun client_closes_the_socket_right_after_reading_the_frame() {
        val host = FakeWindowsPairingHost("closes") { socket ->
            writeFrame(socket, byteArrayOf(5))
            awaitEndOfStream(socket)
        }
        try {
            val result = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", host.port)
            assertTrue("期望成功，实际 " + reasonOf(result), result is PairingFrameResult.Ok)
            assertTrue("客户端读完应立即关闭 socket", host.awaitPeerClosed(2_000))
        } finally {
            host.close()
        }
    }

    @Test
    fun closing_the_host_releases_the_port_and_the_worker_thread() {
        val host = FakeWindowsPairingHost("release") { socket ->
            awaitEndOfStream(socket)
        }
        val port = host.port
        assertTrue(host.loopbackOnly)
        host.close()
        assertTrue(host.isClosed)
        assertTrue("工作线程必须退出", !host.workerAlive)
        val reconnect = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) }
        }
        assertTrue("端口应已释放，实际 " + reconnect.exceptionOrNull(), reconnect.isFailure)
    }

    @Test
    fun invalid_arguments_are_rejected_at_the_boundary() {
        assertTrue(
            runCatching { PairingTransportClient(connectTimeoutMs = 0, readTimeoutMs = 100) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { PairingTransportClient(connectTimeoutMs = 100, readTimeoutMs = -1) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        val client = PairingTransportClient(500, 500)
        assertTrue(
            runCatching { client.exchange(host = "127.0.0.1", port = 0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { client.exchange(host = "127.0.0.1", port = 8942, responseLimit = 0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun invitationJson(
        expectedChallenge: PairingChallenge = challenge(),
        expectedFingerprint: PairingFingerprint = fingerprint(),
        deviceId: String = "device-windows",
        nowEpochMs: Long = 1_792_800_000_000L,
    ): String = PairingPayloadCodec.encode(
        PairingPayload.create(
            deviceId = deviceId,
            ephemeralUrl = "https://127.0.0.1:8942/pairing",
            challenge = expectedChallenge,
            publicKeyFingerprint = expectedFingerprint,
            nowEpochMs = nowEpochMs,
        ),
    )

    private fun challenge(seed: Int = 1): PairingChallenge =
        PairingChallenge.fromBytes(ByteArray(PairingChallenge.BYTE_LENGTH) { index -> (index + seed).toByte() })

    private fun fingerprint(seed: Int = 0x11): PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + seed).toByte() })

    private fun reasonOf(result: PairingFrameResult): String = when (result) {
        is PairingFrameResult.Ok -> "ok"
        is PairingFrameResult.Failed -> result.reason
    }

    private fun reasonOf(result: PairingInvitationResult): String = when (result) {
        is PairingInvitationResult.Ok -> "ok"
        is PairingInvitationResult.Failed -> result.reason
    }

    private fun assertFrameFailed(expected: String, result: PairingFrameResult) {
        assertTrue("期望失败码 " + expected + "，实际 " + reasonOf(result), result is PairingFrameResult.Failed)
        assertEquals(expected, (result as PairingFrameResult.Failed).reason)
    }

    private fun assertInvitationFailed(expected: String, result: PairingInvitationResult) {
        assertTrue("期望失败码 " + expected + "，实际 " + reasonOf(result), result is PairingInvitationResult.Failed)
        assertEquals(expected, (result as PairingInvitationResult.Failed).reason)
    }
}
