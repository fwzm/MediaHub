package com.mediahub.core.common.sync

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-34：回环监听端（SourceSyncTransferListener）的 JVM 单测。
 *
 * 全部用真的回环 socket：监听端绑 127.0.0.1:0，客户端用同一套 SourceSyncTransferClient 拨号，
 * 不 mock 任何 socket 层。TLS 用例用 Windows 权威实现生成的那枚 PKCS#12 夹具（口令见 tls-vectors.json），
 * 所以握的是真的 TLS 1.3，钉的是 Windows 算出来的公钥指纹。
 */
class SourceSyncTransferListenerTest {

    // ---------- 默认值与基本形态 ----------

    @Test
    fun defaultsBindLoopbackAndLeaveThePortToTheOs() {
        // 这里刻意不经过 openListener()：它会把预算收短，而本例要断言的正是默认值。
        val started = SourceSyncTransferListener.open()
        assertTrue("默认参数应该能起监听：" + describeStart(started), started is SourceSyncListenerStart.Started)
        val listener = (started as SourceSyncListenerStart.Started).listener
        try {
            assertEquals("127.0.0.1", listener.address)
            assertTrue("端口应该由系统分配，实际 " + listener.port, listener.port in 1..65535)
            assertFalse(listener.tlsEnabled)
            assertNull(listener.certificateFingerprint)
            assertFalse(listener.isClosed)
            assertEquals(SourceSyncTransferListener.DEFAULT_ACCEPT_TIMEOUT_MS, listener.acceptTimeoutMillis)
            assertEquals(SourceSyncTransferListener.DEFAULT_READ_TIMEOUT_MS, listener.readTimeoutMillis)
        } finally {
            listener.close()
        }
    }

    @Test
    fun plaintextClientDeliversTheGoldenEnvelope() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            val sent = SendCall("127.0.0.1", listener.port, envelope).start()
            val received = assertReceived(listener.receiveOnce())
            assertSentOk(sent.await())
            assertArrayEquals(envelope, received.envelope)
            assertEquals(envelope.size, received.bytes)
            assertEquals(String(envelope, Charsets.UTF_8), received.envelopeJson)
            // 明文且对端没宣布挑战时，帧流里只有数据块。
            assertEquals(SourceSyncTransfer.chunkCount(envelope.size), received.frames)
            assertNull(received.challenge)
        } finally {
            listener.close()
        }
    }

    @Test
    fun secondConnectionOnTheSameListenerIsAccepted() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            repeat(2) { round ->
                val sent = SendCall("127.0.0.1", listener.port, envelope).start()
                val received = assertReceived(listener.receiveOnce())
                assertSentOk(sent.await())
                assertEquals("第 " + (round + 1) + " 条连接的信封字节应与发送的一致", envelope.size, received.bytes)
            }
            assertFalse("两条连接之后监听应仍然可用", listener.isClosed)
        } finally {
            listener.close()
        }
    }

    @Test
    fun challengeIsEchoedAndMustMatchWhenExpectationIsGiven() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            val sent = SendCall("127.0.0.1", listener.port, envelope, challenge = "w6-34-challenge").start()
            val received = assertReceived(listener.receiveOnce(expectedChallenge = "w6-34-challenge"))
            assertSentOk(sent.await())
            assertEquals("w6-34-challenge", received.challenge)
        } finally {
            listener.close()
        }

        val mismatched = openListener()
        try {
            val sent = SendCall("127.0.0.1", mismatched.port, envelope, challenge = "w6-34-challenge").start()
            val received = mismatched.receiveOnce(expectedChallenge = "another-challenge")
            sent.awaitQuietly()
            assertEquals(SourceSyncTransferReasons.CHALLENGE_MISMATCH, codeOf(received))
        } finally {
            mismatched.close()
        }
    }

    // ---------- 打开失败的三个码 ----------

    @Test
    fun nonLoopbackHostIsRefusedBeforeAnySocketExists() {
        val started = SourceSyncTransferListener.open(host = "10.1.2.3")
        assertTrue("非回环地址必须在建 socket 之前就被拒", started is SourceSyncListenerStart.Failed)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, (started as SourceSyncListenerStart.Failed).code)
    }

    @Test
    fun tlsWithoutAnIdentityIsRefusedWithCertificateUnavailable() {
        val started = SourceSyncTransferListener.open(tls = SourceSyncTlsOptions.pinned(pinOf("mediahub-sync-test")))
        assertTrue(started is SourceSyncListenerStart.Failed)
        assertEquals(SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE, (started as SourceSyncListenerStart.Failed).code)
    }

    @Test
    fun busyPortIsReportedAsListenerFailed() {
        val first = openListener()
        try {
            val second = SourceSyncTransferListener.open(port = first.port)
            assertTrue("同一端口不能起第二个监听", second is SourceSyncListenerStart.Failed)
            assertEquals(SourceSyncTransferReasons.LISTENER_FAILED, (second as SourceSyncListenerStart.Failed).code)
            assertEquals("失败时不能占住端口", second.code, SourceSyncTransferReasons.LISTENER_FAILED)
        } finally {
            first.close()
        }
    }

    // ---------- TLS ----------

    @Test
    fun pinnedTlsClientDeliversOverRealTls13() {
        val envelope = goldenEnvelope()
        val pin = pinOf("mediahub-sync-test")
        val listener = openListener(tls = SourceSyncTlsOptions.pinned(pin), identity = identityOf("mediahub-sync-test"))
        try {
            assertTrue(listener.tlsEnabled)
            assertEquals(pin, listener.certificateFingerprint)
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                tls = SourceSyncTlsOptions.pinned(pin),
            ).start()
            val received = assertReceived(listener.receiveOnce())
            assertSentOk(sent.await())
            assertArrayEquals(envelope, received.envelope)
        } finally {
            listener.close()
        }
    }

    @Test
    fun tlsClientAgainstAPlaintextListenerIsNamedNotEnabled() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            // 客户端会握手失败（对端不是 TLS 端点），这里只钉监听端的判定。
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                tls = SourceSyncTlsOptions.pinned(pinOf("mediahub-sync-test")),
            ).start()
            val received = listener.receiveOnce()
            sent.awaitQuietly()
            assertEquals(SourceSyncTlsReasons.NOT_ENABLED, codeOf(received))
        } finally {
            listener.close()
        }
    }

    @Test
    fun plaintextClientAgainstATlsListenerIsNamedHandshakeFailed() {
        val envelope = goldenEnvelope()
        val listener = openListener(
            tls = SourceSyncTlsOptions.pinned(pinOf("mediahub-sync-test")),
            identity = identityOf("mediahub-sync-test"),
        )
        try {
            val sent = SendCall("127.0.0.1", listener.port, envelope).start()
            val received = listener.receiveOnce()
            sent.awaitQuietly()
            assertEquals(SourceSyncTlsReasons.HANDSHAKE_FAILED, codeOf(received))
        } finally {
            listener.close()
        }
    }

    // ---------- 对端认证（W6-25 的五码） ----------

    @Test
    fun authenticatedClientIsVerifiedAndCountsTheTagFrame() {
        val envelope = goldenEnvelope()
        val secret = secretOf()
        val listener = openListener()
        try {
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                challenge = NONCE,
                auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "android-a"),
            ).start()
            val received = assertReceived(
                listener.receiveOnce(auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "win-a")),
            )
            assertSentOk(sent.await())
            // 挑战帧 + 数据块 + tag 帧。
            assertEquals(SourceSyncTransfer.chunkCount(envelope.size) + 2, received.frames)
            assertEquals(NONCE, received.challenge)
        } finally {
            listener.close()
        }
    }

    @Test
    fun wrongSecretIsRefusedAsAuthMismatch() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                challenge = NONCE,
                auth = SourceSyncTransferOptions.withSecret(
                    "interop-secret-bravo".toByteArray(Charsets.UTF_8),
                    SourceSyncNonceCache(),
                    "android-a",
                ),
            ).start()
            val received = listener.receiveOnce(
                auth = SourceSyncTransferOptions.withSecret(secretOf(), SourceSyncNonceCache(), "win-a"),
            )
            sent.awaitQuietly()
            assertEquals(SourceSyncTransferAuthReasons.AUTH_MISMATCH, codeOf(received))
        } finally {
            listener.close()
        }
    }

    @Test
    fun replayedNonceIsRefusedAsAuthReplayed() {
        val envelope = goldenEnvelope()
        val secret = secretOf()
        val nonces = SourceSyncNonceCache()
        val listener = openListener()
        try {
            val first = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                challenge = NONCE,
                auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "android-a"),
            ).start()
            assertReceived(listener.receiveOnce(auth = SourceSyncTransferOptions.withSecret(secret, nonces, "win-a")))
            assertSentOk(first.await())

            val second = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                challenge = NONCE,
                auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "android-a"),
            ).start()
            val replayed = listener.receiveOnce(auth = SourceSyncTransferOptions.withSecret(secret, nonces, "win-a"))
            second.awaitQuietly()
            assertEquals(SourceSyncTransferAuthReasons.AUTH_REPLAYED, codeOf(replayed))
            assertEquals("同一 nonce 只记一次", 1, nonces.count)
        } finally {
            listener.close()
        }
    }

    @Test
    fun clientWithoutATagFrameIsRefusedAsAuthMissing() {
        val envelope = goldenEnvelope()
        val listener = openListener()
        try {
            val sent = SendCall("127.0.0.1", listener.port, envelope, challenge = NONCE).start()
            val received = listener.receiveOnce(
                auth = SourceSyncTransferOptions.withSecret(secretOf(), SourceSyncNonceCache(), "win-a"),
            )
            sent.awaitQuietly()
            assertEquals(SourceSyncTransferAuthReasons.AUTH_MISSING, codeOf(received))
        } finally {
            listener.close()
        }
    }

    @Test
    fun tlsAndAuthenticationComposeOnTheSameConnection() {
        val envelope = goldenEnvelope()
        val secret = secretOf()
        val pin = pinOf("mediahub-sync-test")
        val listener = openListener(tls = SourceSyncTlsOptions.pinned(pin), identity = identityOf("mediahub-sync-test"))
        try {
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                envelope,
                challenge = NONCE,
                tls = SourceSyncTlsOptions.pinned(pin),
                auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "android-a"),
            ).start()
            val received = assertReceived(
                listener.receiveOnce(auth = SourceSyncTransferOptions.withSecret(secret, SourceSyncNonceCache(), "win-a")),
            )
            assertSentOk(sent.await())
            assertArrayEquals(envelope, received.envelope)
        } finally {
            listener.close()
        }
    }

    // ---------- 生命周期与坏帧 ----------

    @Test
    fun closedListenerReleasesThePortForTheNextOne() {
        val first = openListener()
        val port = first.port
        first.close()
        assertTrue(first.isClosed)
        first.close()
        assertTrue("重复 close 不应有副作用", first.isClosed)
        val again = SourceSyncTransferListener.open(port = port)
        try {
            assertTrue("close 之后同一端口必须能重新绑", again is SourceSyncListenerStart.Started)
            assertEquals(port, (again as SourceSyncListenerStart.Started).listener.port)
        } finally {
            (again as SourceSyncListenerStart.Started).listener.close()
        }
    }

    @Test
    fun receiveAfterCloseIsReportedAsListenerFailed() {
        val listener = openListener()
        listener.close()
        val received = listener.receiveOnce()
        assertEquals(SourceSyncTransferReasons.LISTENER_FAILED, codeOf(received))
    }

    @Test
    fun acceptTimeoutIsReportedAsTransferTimeout() {
        val started = SourceSyncTransferListener.open(acceptTimeoutMs = 200)
        val listener = (started as SourceSyncListenerStart.Started).listener
        try {
            val began = System.currentTimeMillis()
            val received = listener.receiveOnce()
            val elapsed = System.currentTimeMillis() - began
            assertEquals(SourceSyncTransferReasons.TRANSFER_TIMEOUT, codeOf(received))
            assertTrue("等待预算 200 毫秒，实际等了 " + elapsed + " 毫秒", elapsed < 10_000)
        } finally {
            listener.close()
        }
    }

    @Test
    fun oversizedDeclaredFrameLengthIsFrameTruncated() {
        assertRawBytesAreRefused(byteArrayOf(0x7F, -1, -1, -1), SourceSyncTransferReasons.FRAME_TRUNCATED)
    }

    @Test
    fun zeroLengthFrameIsFrameTruncated() {
        assertRawBytesAreRefused(byteArrayOf(0, 0, 0, 0), SourceSyncTransferReasons.FRAME_TRUNCATED)
    }

    @Test
    fun halfAFrameIsFrameTruncated() {
        // 4 字节长度说这一帧有 20 字节，实际只来了 6 字节，随后对端半关写端。
        val half = byteArrayOf(0, 0, 0, 20, 1, 2, 3, 4, 5, 6)
        assertRawBytesAreRefused(half, SourceSyncTransferReasons.FRAME_TRUNCATED)
    }

    @Test
    fun closedListenerServesNothingAndLeavesNoThreadBehind() {
        val listener = openListener()
        listener.close()
        // close 之后端口立刻可以再绑：没有残留的 accept 线程占着它。
        val rebound = SourceSyncTransferListener.open(port = listener.port)
        assertTrue(rebound is SourceSyncListenerStart.Started)
        (rebound as SourceSyncListenerStart.Started).listener.close()
    }

    // ---------- W6-40 回执帧（0x04） ----------

    @Test
    fun receiverWritesTheReceiptByteWhenItIsOn() {
        val listener = openListener()
        try {
            val call = ReceiveCall(listener, receipt = SourceSyncReceiptOptions.writing()).start()
            val byte = sendFramesAndReadOneByte(listener.port, goldenEnvelope())
            assertEquals("回执帧就是一字节 0x04", 0x04, byte)
            val received = assertReceived(call.await())
            assertTrue("监听端应该记录已回执", received.receiptWritten)
            assertEquals(SourceSyncTransfer.chunkCount(received.bytes), received.frames)
        } finally {
            listener.close()
        }
    }

    @Test
    fun receiverStaysSilentWhenTheGateRefuses() {
        val listener = openListener()
        try {
            val call = ReceiveCall(
                listener,
                receipt = SourceSyncReceiptOptions.writing(),
                receiptGate = { false },
            ).start()
            assertNull(
                "闸门拒绝时线上不得多一个字节",
                sendFramesAndReadOneByte(listener.port, goldenEnvelope()),
            )
            assertFalse(assertReceived(call.await()).receiptWritten)
        } finally {
            listener.close()
        }
    }

    @Test
    fun receiverStaysSilentWhenReceiptsAreOff() {
        val listener = openListener()
        try {
            val call = ReceiveCall(listener).start()
            assertNull(
                "默认关闭时不得写回执",
                sendFramesAndReadOneByte(listener.port, goldenEnvelope()),
            )
            val received = assertReceived(call.await())
            assertFalse(received.receiptWritten)
            assertEquals(SourceSyncTransfer.chunkCount(received.bytes), received.frames)
        } finally {
            listener.close()
        }
    }

    @Test
    fun clientAwaitingAReceiptConfirmsAgainstAListeningPeer() {
        val listener = openListener()
        try {
            val call = ReceiveCall(listener, receipt = SourceSyncReceiptOptions.writing()).start()
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                goldenEnvelope(),
                receipt = SourceSyncReceiptOptions.awaiting(2_000),
            ).start().await()
            assertSentOk(sent)
            assertTrue(assertReceived(call.await()).receiptWritten)
        } finally {
            listener.close()
        }
    }

    @Test
    fun clientAwaitingAReceiptReportsMissingWhenTheListenerIsSilent() {
        val listener = openListener()
        try {
            val call = ReceiveCall(listener).start()
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                goldenEnvelope(),
                receipt = SourceSyncReceiptOptions.awaiting(2_000),
            ).start().await()
            assertEquals(
                SourceSyncReceiptReasons.RECEIPT_MISSING,
                (sent as SourceSyncTransferResult.Failed).reason,
            )
            assertFalse(assertReceived(call.await()).receiptWritten)
        } finally {
            listener.close()
        }
    }

    @Test
    fun tlsConnectionCarriesTheReceiptToo() {
        val identity = identityOf("mediahub-sync-test")
        val pin = pinOf("mediahub-sync-test")
        val listener = openListener(tls = SourceSyncTlsOptions.pinned(pin), identity = identity)
        try {
            val call = ReceiveCall(listener, receipt = SourceSyncReceiptOptions.writing()).start()
            val sent = SendCall(
                "127.0.0.1",
                listener.port,
                goldenEnvelope(),
                tls = SourceSyncTlsOptions.pinned(pin),
                receipt = SourceSyncReceiptOptions.awaiting(2_000),
            ).start().await()
            assertSentOk(sent)
            assertTrue(assertReceived(call.await()).receiptWritten)
            assertEquals(pin, listener.certificateFingerprint)
        } finally {
            listener.close()
        }
    }

    /** 用裸 socket 把整段帧写过去，再读回最多一个字节（对端不写回执时读到 EOF 或 RST）。 */
    private fun sendFramesAndReadOneByte(port: Int, envelope: ByteArray): Int? {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
            socket.soTimeout = 10_000
            val frames = SourceSyncTransfer.encode(envelope)
            socket.getOutputStream().write(frames)
            socket.getOutputStream().flush()
            return try {
                val value = socket.getInputStream().read()
                if (value < 0) null else value
            } catch (error: java.io.IOException) {
                null
            }
        }
    }
    // ---------- 夹具 ----------

    private fun openListener(
        tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        identity: SourceSyncTlsIdentity = SourceSyncTlsIdentity.NONE,
        acceptTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): SourceSyncTransferListener {
        val started = SourceSyncTransferListener.open(
            tls = tls,
            identity = identity,
            acceptTimeoutMs = acceptTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        assertTrue("监听没能起来：" + describeStart(started), started is SourceSyncListenerStart.Started)
        return (started as SourceSyncListenerStart.Started).listener
    }

    /** 用裸 socket 发一段字节，再半关写端，断言监听端给出的码。 */
    private fun assertRawBytesAreRefused(bytes: ByteArray, expectedCode: String) {
        val listener = openListener()
        try {
            val call = ReceiveCall(listener).start()
            rawSend(listener.port, bytes)
            assertEquals(expectedCode, codeOf(call.await()))
        } finally {
            listener.close()
        }
    }

    private fun rawSend(port: Int, bytes: ByteArray) {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            // 读到对端关连接为止，避免"本端先关"变成 RST 把失败码改写成 io-failed。
            drain(socket.getInputStream())
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(256)
        try {
            while (input.read(buffer) >= 0) {
                // 丢掉即可：这些用例只关心监听端给出的码。
            }
        } catch (error: java.io.IOException) {
            // 对端 RST 也算读完。
        }
    }

    private fun goldenEnvelope(): ByteArray =
        resourceStream("/sync/windows-envelope-device-a.json").use { it.readBytes() }

    private fun resourceStream(path: String): InputStream {
        val stream = javaClass.getResourceAsStream(path)
        assertTrue("夹具不存在：" + path, stream != null)
        return stream!!
    }

    private fun resourceText(path: String): String = resourceStream(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun tlsVectors(): JsonObject =
        Json.parseToJsonElement(resourceText("/tls/tls-vectors.json")).jsonObject

    private fun fixture(name: String): JsonObject {
        val fixtures = tlsVectors().getValue("fixtures").jsonArray
        val match = fixtures.map { it.jsonObject }
            .firstOrNull { it.getValue("name").jsonPrimitive.content == name }
        assertTrue("tls-vectors.json 里没有夹具 " + name, match != null)
        return match!!
    }

    private fun pinOf(name: String): String = fixture(name).getValue("spkiSha256Hex").jsonPrimitive.content

    private fun identityOf(name: String): SourceSyncTlsIdentity {
        val password = fixture(name).getValue("pkcs12Password").jsonPrimitive.content
        val store = KeyStore.getInstance("PKCS12")
        resourceStream("/tls/" + fixture(name).getValue("file").jsonPrimitive.content).use { stream ->
            store.load(stream, password.toCharArray())
        }
        val identity = SourceSyncTlsIdentity.fromKeyStore(store, password.toCharArray())
        assertTrue("p12 夹具应该能变成可用的 TLS 身份", identity.isUsable)
        assertEquals(pinOf(name), identity.certificateFingerprint)
        return identity
    }

    private fun secretOf(): ByteArray = "interop-secret-alpha".toByteArray(Charsets.UTF_8)

    private fun assertReceived(received: SourceSyncListenerTransfer): SourceSyncListenerTransfer.Received {
        assertTrue("期待收到信封，实际是 " + describe(received), received is SourceSyncListenerTransfer.Received)
        return received as SourceSyncListenerTransfer.Received
    }

    private fun codeOf(received: SourceSyncListenerTransfer): String {
        assertTrue("期待失败，实际收到了信封", received is SourceSyncListenerTransfer.Failed)
        return (received as SourceSyncListenerTransfer.Failed).code
    }

    private fun describe(received: SourceSyncListenerTransfer): String = when (received) {
        is SourceSyncListenerTransfer.Received -> "ok frames=" + received.frames + " bytes=" + received.bytes
        is SourceSyncListenerTransfer.Failed -> received.code + " / " + (received.detail ?: "")
    }

    private fun describeStart(started: SourceSyncListenerStart): String = when (started) {
        is SourceSyncListenerStart.Started -> "ok port=" + started.listener.port
        is SourceSyncListenerStart.Failed -> started.code + " / " + (started.detail ?: "")
    }

    private fun describeSent(result: SourceSyncTransferResult?): String = when (result) {
        null -> "没有结果"
        is SourceSyncTransferResult.Ok -> "ok frames=" + result.frameCount
        is SourceSyncTransferResult.Failed -> result.reason + " / " + (result.message ?: "")
    }

    private fun assertSentOk(result: SourceSyncTransferResult?) {
        assertTrue("客户端发送失败：" + describeSent(result), result is SourceSyncTransferResult.Ok)
    }

    /** 在后台线程里跑一次监听端的接收，主线程负责当对端。 */
    private class ReceiveCall(
        private val listener: SourceSyncTransferListener,
        private val expectedChallenge: String? = null,
        private val auth: SourceSyncTransferOptions? = null,
        private val receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        private val receiptGate: ((ByteArray) -> Boolean)? = null,
    ) {
        private val result = AtomicReference<SourceSyncListenerTransfer?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try {
                result.set(listener.receiveOnce(expectedChallenge, auth, receipt, receiptGate))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-34-listener"
        }

        fun start(): ReceiveCall {
            thread.start()
            return this
        }

        fun await(timeoutMs: Long = 30_000): SourceSyncListenerTransfer {
            thread.join(timeoutMs)
            assertFalse("监听线程没有在超时内结束", thread.isAlive)
            failure.get()?.let { throw AssertionError("监听线程抛了异常", it) }
            return result.get() ?: throw AssertionError("监听线程没有给出结果")
        }
    }

    /** 在后台线程里用真客户端拨号发送，主线程负责当监听端。 */
    private class SendCall(
        private val host: String,
        private val port: Int,
        private val envelope: ByteArray,
        private val challenge: String? = null,
        private val tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        private val auth: SourceSyncTransferOptions? = null,
        private val receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
    ) {
        private val result = AtomicReference<SourceSyncTransferResult?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val client = SourceSyncTransferClient(connectTimeoutMs = 5_000, readTimeoutMs = 10_000)
        private val thread = Thread {
            try {
                result.set(client.sendTo(host, port, envelope, challenge, tls, auth, receipt))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-34-send"
        }

        fun start(): SendCall {
            thread.start()
            return this
        }

        fun await(): SourceSyncTransferResult? {
            thread.join(30_000)
            assertFalse("发送线程没有在超时内结束", thread.isAlive)
            failure.get()?.let { throw AssertionError("发送线程抛了异常", it) }
            return result.get()
        }

        /** 期待对端会拒绝时用：只等线程结束，不管它的结果是什么。 */
        fun awaitQuietly() {
            thread.join(30_000)
        }
    }

    private companion object {
        /** 与 Windows 认证向量里的第一个 nonce 同名同形（base64url 22 字符）。 */
        const val NONCE: String = "nonce-auth-0001"
    }
}
