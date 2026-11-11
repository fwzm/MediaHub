package com.mediahub.core.common.sync

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 回环端到端：真的开 ServerSocket(0, 8, 127.0.0.1)，把 Windows 生成的真实帧流回放给本端客户端；
 * 反向再由客户端把信封送到一个用 SourceSyncTransfer.receive 收的对端。
 *
 * 这里只走回环：iOS/Android 没有服务端监听能力，传输方向永远是「Windows 监听、本端连接」，
 * 所以客户端只允许连回环地址这一条约束本身也是被钉住的（non_loopback_hosts_never_reach_the_socket）。
 */
class SourceSyncTransferLoopbackTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(resource("windows-transfer-vectors.json")).jsonObject
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .use { it.readBytes() }

    private fun frozenChallenge(): String = vectors.getValue("frozenChallenge").jsonPrimitive.content

    private fun goldenFrames(): ByteArray = hex(
        vectors.getValue("vectors").jsonObject.getValue("withChallenge").jsonObject
            .getValue("framesHex").jsonPrimitive.content,
    )

    private fun hex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0)
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            require(high >= 0 && low >= 0)
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun reasonOf(result: SourceSyncTransferResult): String = when (result) {
        is SourceSyncTransferResult.Ok -> "ok"
        is SourceSyncTransferResult.Failed -> result.reason
    }

    private fun okOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Ok {
        if (result is SourceSyncTransferResult.Failed) {
            throw AssertionError(what + " 本应成功，实际失败：" + result.reason + " / " + result.message)
        }
        return result as SourceSyncTransferResult.Ok
    }

    // ------------------------------------------------------------------ 正向：收 / 发

    @Test
    fun receives_the_golden_vector_from_a_loopback_peer() {
        val frames = goldenFrames()
        val server = LoopbackPeer { socket ->
            socket.getOutputStream().write(frames)
            socket.getOutputStream().flush()
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val result = okOrFail(client.receiveFrom("127.0.0.1", server.port, frozenChallenge()), "回环收信封")
            assertTrue(
                "回环收到的信封必须与 Windows 送出的逐字节相同",
                resourceBytes("windows-envelope-device-a.json").contentEquals(result.envelope),
            )
            assertEquals(frozenChallenge(), result.challenge)
            assertEquals(2, result.frameCount)
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun sends_an_envelope_a_loopback_peer_reassembles() {
        val envelope = resourceBytes("windows-envelope-device-a.json")
        val received = AtomicReference<SourceSyncTransferResult?>(null)
        val server = LoopbackPeer { socket ->
            received.set(SourceSyncTransfer.receive(socket.getInputStream(), frozenChallenge()))
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val sent = okOrFail(client.sendTo("localhost", server.port, envelope, frozenChallenge()), "回环发信封")
            assertEquals(2, sent.frameCount)
            server.await()
            val remote = okOrFail(checkNotNull(received.get()) { "对端没有收到结果" }, "对端收信封")
            assertTrue("对端重组出的信封必须与发出的相同", envelope.contentEquals(remote.envelope))
            assertEquals(frozenChallenge(), remote.challenge)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun a_mismatched_challenge_is_reported_before_the_envelope_is_accepted() {
        val frames = goldenFrames()
        val server = LoopbackPeer { socket ->
            socket.getOutputStream().write(frames)
            socket.getOutputStream().flush()
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val result = client.receiveFrom("127.0.0.1", server.port, "some-other-challenge")
            assertEquals(SourceSyncTransferReasons.CHALLENGE_MISMATCH, reasonOf(result))
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    // ------------------------------------------------------------------ 反向：不该拨号 / 该超时

    @Test
    fun non_loopback_hosts_never_reach_the_socket() {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = server.localPort
        server.soTimeout = 400
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 500)
            assertEquals(
                SourceSyncTransferReasons.NOT_LOOPBACK,
                reasonOf(client.receiveFrom("192.168.1.20", port, null)),
            )
            assertEquals(
                SourceSyncTransferReasons.NOT_LOOPBACK,
                reasonOf(client.sendTo("example.com", port, ByteArray(16), null)),
            )
            try {
                server.accept()
                fail("非回环地址绝不该真的连到监听端")
            } catch (expected: SocketTimeoutException) {
                assertTrue("监听端 400ms 内没有收到任何连接", true)
            }
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun a_silent_peer_times_out_with_transfer_timeout() {
        val server = LoopbackPeer { socket ->
            socket.soTimeout = 3_000
            Thread.sleep(1_200)
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 200)
            val result = client.receiveFrom("127.0.0.1", server.port, frozenChallenge())
            assertEquals(SourceSyncTransferReasons.TRANSFER_TIMEOUT, reasonOf(result))
            server.await(4_000)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun the_socket_is_released_after_a_timeout() {
        // 对端不写任何东西，只等客户端自己断：读回 -1 就证明客户端关掉了 socket。
        val server = LoopbackPeer { socket ->
            socket.soTimeout = 4_000
            assertEquals("超时后客户端必须关闭 socket", -1, socket.getInputStream().read())
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 200)
            assertEquals(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                reasonOf(client.receiveFrom("127.0.0.1", server.port, null)),
            )
            server.await(5_000)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun a_closed_port_reports_connect_failed() {
        val probe = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = probe.localPort
        probe.close()
        val client = SourceSyncTransferClient(connectTimeoutMs = 1_000, readTimeoutMs = 1_000)
        val result = client.receiveFrom("127.0.0.1", port, null)
        assertEquals(SourceSyncTransferReasons.CONNECT_FAILED, reasonOf(result))
    }

    @Test
    fun ports_outside_the_valid_range_are_connect_failed() {
        val client = SourceSyncTransferClient(connectTimeoutMs = 300, readTimeoutMs = 300)
        for (port in listOf(0, -1, 70_000)) {
            assertEquals(
                "端口 " + port + " 必须在拨号前就被拒",
                SourceSyncTransferReasons.CONNECT_FAILED,
                reasonOf(client.receiveFrom("127.0.0.1", port, null)),
            )
        }
    }

    @Test
    fun ipv6_loopback_literal_is_not_treated_as_remote() {
        val client = SourceSyncTransferClient(connectTimeoutMs = 300, readTimeoutMs = 300)
        // ::1 上没有监听端，但判定层必须先把它认成回环：错误码只能是连接类，不能是 not-loopback。
        assertNotEquals(
            "::1 是回环地址",
            SourceSyncTransferReasons.NOT_LOOPBACK,
            reasonOf(client.receiveFrom("::1", 65_000, null)),
        )
    }

    @Test
    fun default_timeouts_align_with_the_windows_listener() {
        assertEquals(3_000, SourceSyncTransferClient.DEFAULT_CONNECT_TIMEOUT_MS)
        assertEquals(15_000, SourceSyncTransferClient.DEFAULT_READ_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------ 回环对端helper

    /** 绑 127.0.0.1:0，接受一个连接后交给 handler，随后关闭连接与监听端。 */
    private class LoopbackPeer(handler: (Socket) -> Unit) {

        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val failure = AtomicReference<Throwable?>(null)
        private val thread: Thread

        val port: Int = server.localPort

        init {
            thread = Thread {
                try {
                    server.accept().use { socket -> handler(socket) }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    runCatching { server.close() }
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        fun await(timeoutMs: Long = 5_000) {
            thread.join(timeoutMs)
            if (thread.isAlive) throw AssertionError("回环对端线程 " + timeoutMs + "ms 内没有结束")
            failure.get()?.let { throw AssertionError("回环对端失败：" + it.message, it) }
        }

        fun closeQuietly() {
            runCatching { server.close() }
        }
    }
}
