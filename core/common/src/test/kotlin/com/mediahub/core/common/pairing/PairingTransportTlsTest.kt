package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.SourceSyncTls
import com.mediahub.core.common.sync.SourceSyncTlsIdentity
import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.SourceSyncTlsResult
import com.mediahub.core.common.sync.SourceSyncTlsSession
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对传输的 TLS 1.3 + 公钥指纹钉扎（W6-59b，对齐 Windows PairingTransportClient）。
 *
 * 服务端不是 mock：本文件用真 SSLContext（夹具证书来自 tls/mediahub-sync-test.p12）在 127.0.0.1:0
 * 上做真握手，客户端走 PairingTransportClient 的 TLS 路径。
 *
 * 纪律：只绑回环、只连回环；每条用例都 close()（关监听 + 在途 socket + join 工作线程），不留后台线程。
 */
class PairingTransportTlsTest {

    private val password: String = "mediahub-tls-test"

    private val goodFixture: String = "mediahub-sync-test"

    private val otherFixture: String = "mediahub-sync-other"

    private val goodPin: String = pinOf(goodFixture)

    private fun resourceBytes(path: String): ByteArray {
        val stream = javaClass.getResourceAsStream(path)
        checkNotNull(stream) { "缺少测试资源 " + path }
        return stream.use { it.readBytes() }
    }

    private fun resourceJson(): String =
        String(resourceBytes("/tls/tls-vectors.json"), Charsets.UTF_8)

    private fun pinOf(name: String): String {
        val text = resourceJson()
        val marker = "\"" + name + "\""
        val start = text.indexOf(marker)
        check(start >= 0) { "向量里没有 " + name }
        val valueStart = text.indexOf("spkiSha256Hex", start)
        check(valueStart >= 0) { "向量里没有 spkiSha256Hex" }
        val colon = text.indexOf(":", valueStart)
        val firstQuote = text.indexOf('"', colon)
        val secondQuote = text.indexOf('"', firstQuote + 1)
        return text.substring(firstQuote + 1, secondQuote)
    }

    private fun p12Of(name: String): ByteArray = resourceBytes("/tls/" + name + ".p12")

    private fun keyStoreOf(name: String): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(p12Of(name)), password.toCharArray())
        return store
    }

    private fun identityOf(name: String): SourceSyncTlsIdentity =
        SourceSyncTlsIdentity.fromKeyStore(keyStoreOf(name), password.toCharArray())

    private fun client(readTimeoutMs: Int = 4_000): PairingTransportClient =
        PairingTransportClient(connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs)

    private fun reasonOf(result: PairingFrameResult): String = when (result) {
        is PairingFrameResult.Ok -> "ok"
        is PairingFrameResult.Failed -> result.reason
    }

    private fun invitationReasonOf(result: PairingInvitationResult): String = when (result) {
        is PairingInvitationResult.Ok -> "ok"
        is PairingInvitationResult.Failed -> result.reason
    }

    private fun invitationText(
        deviceId: String,
        challenge: PairingChallenge,
        fingerprint: PairingFingerprint,
    ): String {
        val payload = PairingPayload.create(
            deviceId = deviceId,
            ephemeralUrl = "https://127.0.0.1:8443/pairing",
            challenge = challenge,
            publicKeyFingerprint = fingerprint,
            nowEpochMs = 1_760_000_000_000L,
        )
        return PairingPayloadCodec.encode(payload)
    }

    private class FakeTlsPairingHost(
        label: String,
        private val identity: SourceSyncTlsIdentity,
        private val respondent: FakeTlsPairingHost.(SourceSyncTlsSession) -> Unit,
    ) : AutoCloseable {

        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val lock = Any()
        private val worker = Thread({ serve() }, "fake-pairing-tls-" + label)
        private var accepted = 0
        private var inFlight: Socket? = null

        @Volatile
        private var responderFailure: Throwable? = null

        @Volatile
        private var handshakeFailure: String? = null

        init {
            worker.isDaemon = true
            worker.start()
        }

        val port: Int get() = server.localPort

        val acceptedCount: Int get() = synchronized(lock) { accepted }

        val failure: Throwable? get() = responderFailure

        val handshakeCode: String? get() = handshakeFailure

        private fun serve() {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: IOException) {
                    return
                }
                synchronized(lock) {
                    accepted += 1
                    inFlight = socket
                }
                var session: SourceSyncTlsSession? = null
                try {
                    socket.soTimeout = 20_000
                    val handshake = SourceSyncTls.handshakeServer(socket, identity, timeoutMs = 3_000)
                    if (handshake is SourceSyncTlsResult.Failed) {
                        handshakeFailure = handshake.reason
                    } else {
                        val opened = (handshake as SourceSyncTlsResult.Ok).session
                        session = opened
                        respondent.invoke(this, opened)
                    }
                } catch (error: Throwable) {
                    responderFailure = error
                } finally {
                    synchronized(lock) { inFlight = null }
                    val held = session
                    if (held != null) {
                        SourceSyncTls.gracefulClose(held.socket, 1_000)
                    } else {
                        closeQuietly(socket)
                    }
                }
            }
        }

        fun writeFrame(session: SourceSyncTlsSession, payload: ByteArray) {
            PairingFrameIo.writeFrame(session.output, payload)
            session.output.flush()
        }

        fun writeRaw(session: SourceSyncTlsSession, bytes: ByteArray) {
            session.output.write(bytes)
            session.output.flush()
        }

        fun readFrame(session: SourceSyncTlsSession): ByteArray? =
            when (val read = PairingFrameIo.readFrame(session.input, PairingFrame.MAXIMUM_PAYLOAD_BYTES)) {
                is FrameRead.Payload -> read.bytes
                is FrameRead.Rejected -> null
            }

        override fun close() {
            closeQuietly(server)
            synchronized(lock) { inFlight?.let { closeQuietly(it) } }
            worker.join(4_000)
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable == null) return
            try {
                closeable.close()
            } catch (error: IOException) {
            }
        }
    }

    @Test
    fun withoutTlsOptionsTheBytesAreTheOldPlaintextExchange() {
        val host = FakeWindowsPairingHost(label = "plain") { socket ->
            writeFrame(socket, "plain-ok".toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().exchange("127.0.0.1", host.port)
            assertEquals("ok", reasonOf(result))
            assertEquals("plain-ok", String((result as PairingFrameResult.Ok).payload, Charsets.UTF_8))
            assertEquals(1, host.acceptedCount)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun aPinnedFingerprintDrivesARealTls13HandshakeAndTheFrameStillArrives() {
        val host = FakeTlsPairingHost("pinned-ok", identityOf(goodFixture)) { session ->
            writeFrame(session, "tls-ok".toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().exchange(
                "127.0.0.1",
                host.port,
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals("ok", reasonOf(result))
            assertEquals("tls-ok", String((result as PairingFrameResult.Ok).payload, Charsets.UTF_8))
            assertEquals(1, host.acceptedCount)
            assertNull(host.failure)
            assertNull(host.handshakeCode)
        } finally {
            host.close()
        }
    }

    @Test
    fun aPinnedFingerprintThatIsNotThePeerCertificateIsFingerprintMismatch() {
        val host = FakeTlsPairingHost("pinned-wrong", identityOf(goodFixture)) { session ->
            writeFrame(session, "should-never-arrive".toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().exchange(
                "127.0.0.1",
                host.port,
                tls = PairingTlsOptions.Pinned(pinOf(otherFixture)),
            )
            assertEquals(PairingTransportReasons.FINGERPRINT_MISMATCH, reasonOf(result))
        } finally {
            host.close()
        }
    }

    @Test
    fun aMalformedPinIsRefusedBeforeTheSocketIsDialled() {
        val host = FakeWindowsPairingHost(label = "bad-pin") { socket ->
            writeFrame(socket, "never".toByteArray(Charsets.UTF_8))
        }
        try {
            val bad = listOf("", "   ", "not-a-fingerprint", goodPin.substring(0, 32))
            for (candidate in bad) {
                val result = client().exchange(
                    "127.0.0.1",
                    host.port,
                    tls = PairingTlsOptions.Pinned(candidate),
                )
                assertEquals(candidate, PairingReasons.BAD_FINGERPRINT, reasonOf(result))
            }
            assertEquals(0, host.acceptedCount)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun askingForTlsAgainstAPlaintextPeerIsTlsVersion() {
        val host = FakeWindowsPairingHost(label = "plaintext-peer") { socket ->
            writeFrame(socket, "plaintext".toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client(readTimeoutMs = 2_000).exchange(
                "127.0.0.1",
                host.port,
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals(PairingTransportReasons.TLS_VERSION, reasonOf(result))
        } finally {
            host.close()
        }
    }

    @Test
    fun aPlaintextClientAgainstATlsPeerTimesOutInsteadOfSilentlySucceeding() {
        // 明文这一侧没有握手，它只能观察「在超时之前没有帧到达」，所以稳定码是 peer-timeout，不是 payload-invalid：
        // 对端是 TLS 时它会一直等 ClientHello，而本端在等一个帧，谁都不先说话。要点是绝不静默成功，也不静默降级成明文。
        val host = FakeTlsPairingHost("tls-against-plain", identityOf(goodFixture)) { session ->
            writeFrame(session, "tls-only".toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client(readTimeoutMs = 800).exchange("127.0.0.1", host.port)
            assertEquals(PairingTransportReasons.PEER_TIMEOUT, reasonOf(result))
        } finally {
            host.close()
        }
    }

    @Test
    fun aRequestFrameTravelsOverTlsAndTheReplyComesBack() {
        val host = FakeTlsPairingHost("request-over-tls", identityOf(goodFixture)) { session ->
            val request = readFrame(session) ?: ByteArray(0)
            val echoed = "echo:" + String(request, Charsets.UTF_8)
            writeFrame(session, echoed.toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().exchange(
                "127.0.0.1",
                host.port,
                request = "hello".toByteArray(Charsets.UTF_8),
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals("ok", reasonOf(result))
            assertEquals("echo:hello", String((result as PairingFrameResult.Ok).payload, Charsets.UTF_8))
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun fetchInvitationWorksOverTlsAndKeepsTheCodeVocabulary() {
        val challenge = PairingChallenge.createRandom()
        val fingerprint = PairingFingerprint.parse(goodPin)
        val text = invitationText("windows-a", challenge, fingerprint)
        val host = FakeTlsPairingHost("invite-tls", identityOf(goodFixture)) { session ->
            writeFrame(session, text.toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge,
                expectedFingerprint = fingerprint,
                nowEpochMs = 1_760_000_030_000L,
                tls = PairingTlsOptions.pinned(goodPin),
            )
            assertEquals("ok", invitationReasonOf(result))
            val ok = result as PairingInvitationResult.Ok
            assertEquals("windows-a", ok.payload.deviceId)
            assertEquals(text, ok.json)
            assertNull(host.failure)
        } finally {
            host.close()
        }
    }

    @Test
    fun thePinnedFingerprintIsAlsoCheckedAgainstThePayload() {
        val challenge = PairingChallenge.createRandom()
        val pinned = PairingFingerprint.parse(goodPin)
        val other = PairingFingerprint.parse(pinOf(otherFixture))
        val text = invitationText("windows-a", challenge, other)
        val host = FakeTlsPairingHost("invite-wrong-payload", identityOf(goodFixture)) { session ->
            writeFrame(session, text.toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge,
                expectedFingerprint = pinned,
                nowEpochMs = 1_760_000_030_000L,
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals(PairingTransportReasons.FINGERPRINT_MISMATCH, invitationReasonOf(result))
        } finally {
            host.close()
        }
    }

    @Test
    fun anExpiredInvitationOverTlsIsStillLifetimeExceeded() {
        val challenge = PairingChallenge.createRandom()
        val fingerprint = PairingFingerprint.parse(goodPin)
        val text = invitationText("windows-a", challenge, fingerprint)
        val host = FakeTlsPairingHost("invite-expired", identityOf(goodFixture)) { session ->
            writeFrame(session, text.toByteArray(Charsets.UTF_8))
        }
        try {
            val result = client().fetchInvitation(
                host = "127.0.0.1",
                port = host.port,
                expectedChallenge = challenge,
                expectedFingerprint = fingerprint,
                nowEpochMs = 1_760_000_120_000L,
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals(PairingTransportReasons.LIFETIME_EXCEEDED, invitationReasonOf(result))
        } finally {
            host.close()
        }
    }

    @Test
    fun theTlsOptionsNeverPrintThePin() {
        val pinned = PairingTlsOptions.Pinned(goodPin)
        assertTrue(pinned.enabled)
        assertEquals(goodPin, pinned.expectedFingerprint)
        assertNotNull(pinned.fingerprint)
        assertTrue(pinned.toString().contains("<redacted>"))
        assertTrue(!pinned.toString().contains(goodPin))
        assertTrue(!PairingTlsOptions.Off.enabled)
        assertEquals("PairingTlsOptions { Enabled = false }", PairingTlsOptions.Off.toString())
        val blank = PairingTlsOptions.pinned("  ")
        assertTrue(blank.enabled)
        assertTrue(!blank.hasPin)
    }

    @Test
    fun theUnderlyingTlsCodesCollapseOntoThePairingVocabulary() {
        assertEquals(
            PairingTransportReasons.FINGERPRINT_MISMATCH,
            pairingTlsReasonOf(SourceSyncTlsReasons.FINGERPRINT_MISMATCH),
        )
        val others = listOf(
            SourceSyncTlsReasons.HANDSHAKE_FAILED,
            SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
            SourceSyncTlsReasons.NOT_ENABLED,
        )
        for (other in others) {
            assertEquals(other, PairingTransportReasons.TLS_VERSION, pairingTlsReasonOf(other))
        }
        assertEquals(7, PairingTransportReasons.ALL.size)
        assertTrue(!PairingTransportReasons.ALL.contains("tls-handshake-failed"))
        assertTrue(!PairingTransportReasons.ALL.contains("tls-certificate-unavailable"))
    }

    @Test
    fun theTlsSessionIsClosedGracefullySoThePeerSeesEndOfStream() {
        val sawEnd = CountDownLatch(1)
        val host = FakeTlsPairingHost("graceful-close", identityOf(goodFixture)) { session ->
            writeFrame(session, "bye".toByteArray(Charsets.UTF_8))
            val buffer = ByteArray(64)
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                session.socket.soTimeout = 100
                val read = try {
                    session.input.read(buffer)
                } catch (timeout: java.net.SocketTimeoutException) {
                    continue
                } catch (error: IOException) {
                    sawEnd.countDown()
                    return@FakeTlsPairingHost
                }
                if (read < 0) {
                    sawEnd.countDown()
                    return@FakeTlsPairingHost
                }
            }
        }
        try {
            val result = client().exchange(
                "127.0.0.1",
                host.port,
                tls = PairingTlsOptions.Pinned(goodPin),
            )
            assertEquals("ok", reasonOf(result))
            assertEquals("bye", String((result as PairingFrameResult.Ok).payload, Charsets.UTF_8))
            assertTrue(sawEnd.await(4, TimeUnit.SECONDS))
        } finally {
            host.close()
        }
    }
}
