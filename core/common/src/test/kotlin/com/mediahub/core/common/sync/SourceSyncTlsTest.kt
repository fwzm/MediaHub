package com.mediahub.core.common.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TLS 1.3 + 公钥指纹钉扎（W6-29，对齐 Windows W6-26）的真握手对照。
 *
 * 夹具不是手搓的：资源 tls/mediahub-sync-test.p12 与 tls/mediahub-sync-other.p12 由
 * D:/mh-verify/tls-vectors（临时 console 工程，不入库）用 .NET 的 CertificateRequest 生成，
 * 期望指纹由 Windows 权威实现 PairingCertificate.ComputeFingerprint 算出后写进 tls/tls-vectors.json；
 * 本文件用 JVM 的 SSLContext 真起 SSLServerSocket 做回环握手——不是 mock，也不是伪造字节。
 *
 * 对照口径：码名逐字相等、顺序相等；诊断文案是本地化的，不比对文本（只在「码」上断言）。
 */
class SourceSyncTlsTest {

    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(resourceText("/tls/tls-vectors.json")).jsonObject
    }

    private val password: String = "mediahub-tls-test"

    // ---------------------------------------------------------------- 资源与夹具

    private fun resourceStream(path: String): InputStream =
        checkNotNull(javaClass.getResourceAsStream(path)) { "缺少测试资源 " + path }

    private fun resourceText(path: String): String =
        resourceStream(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resourceBytes(path: String): ByteArray = resourceStream(path).use { it.readBytes() }

    private fun fixture(name: String): JsonObject =
        vectors.getValue("fixtures").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == name }

    private fun pinOf(name: String): String = fixture(name).getValue("spkiSha256Hex").jsonPrimitive.content

    private fun p12Of(name: String): ByteArray =
        resourceBytes("/tls/" + fixture(name).getValue("file").jsonPrimitive.content)

    private fun keyStoreOf(name: String): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(p12Of(name)), password.toCharArray())
        return store
    }

    private fun serverContext(name: String, protocol: String = "TLSv1.3"): SSLContext {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStoreOf(name), password.toCharArray())
        val context = SSLContext.getInstance(protocol)
        context.init(factory.keyManagers, null, null)
        return context
    }

    private fun tlsListener(context: SSLContext, protocols: Array<String> = arrayOf("TLSv1.3")): SSLServerSocket {
        val listener = context.serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.enabledProtocols = protocols
        listener.soTimeout = 20_000
        return listener
    }

    private fun plainListener(): ServerSocket =
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { it.soTimeout = 20_000 }

    private fun identityOf(name: String): SourceSyncTlsIdentity =
        SourceSyncTlsIdentity.fromKeyStore(keyStoreOf(name), password.toCharArray())

    private fun goldenEnvelope(): ByteArray = resourceBytes("/sync/windows-envelope-device-a.json")

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append(Character.forDigit((byte.toInt() shr 4) and 0x0F, 16))
            builder.append(Character.forDigit(byte.toInt() and 0x0F, 16))
        }
        return builder.toString()
    }

    /** 向量文件在 Windows 检出里是 CRLF、在 Linux 检出里是 LF；比哈希前先归一，避免假红。 */
    private fun lfNormalized(bytes: ByteArray): ByteArray =
        String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)

    private fun trustAllContext(protocol: String = "TLS"): SSLContext {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance(protocol)
        context.init(null, arrayOf<TrustManager>(trust), null)
        return context
    }

    // ---------------------------------------------------------------- 对端线程

    private class Peer(val thread: Thread, val failure: AtomicReference<Throwable?>) {

        fun await(timeoutMs: Long = 30_000) {
            thread.join(timeoutMs)
            if (thread.isAlive) {
                throw AssertionError("对端线程在 " + timeoutMs + " 毫秒内没有结束")
            }
            failure.get()?.let { throw AssertionError("对端线程失败", it) }
        }
    }

    private fun startPeer(name: String, tolerateFailures: Boolean, block: () -> Unit): Peer {
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread({
            try {
                block()
            } catch (error: Throwable) {
                if (!tolerateFailures) failure.set(error)
            }
        }, name)
        thread.isDaemon = true
        thread.start()
        return Peer(thread, failure)
    }

    /** 接一条连接并把它交给 handler；handler 抛错算作对端失败。 */
    private fun serve(listener: ServerSocket, handler: (Socket) -> Unit): Peer =
        startPeer("source-sync-tls-peer", tolerateFailures = false) {
            try {
                listener.accept().use { handler(it) }
            } finally {
                runCatching { listener.close() }
            }
        }

    /** 用于「对端本来就该失败」的用例：异常不记为失败。 */
    private fun serveTolerantly(listener: ServerSocket, handler: (Socket) -> Unit): Peer =
        startPeer("source-sync-tls-tolerated-peer", tolerateFailures = true) {
            try {
                val accepted = try {
                    listener.accept()
                } catch (error: Throwable) {
                    null
                }
                if (accepted != null) {
                    accepted.use { socket -> runCatching { handler(socket) } }
                }
            } finally {
                runCatching { listener.close() }
            }
        }

    // ---------------------------------------------------------------- A. 码表与选项

    @Test
    fun reasonsAreTheFrozenFourInOrder() {
        assertEquals(
            listOf(
                "tls-handshake-failed",
                "tls-fingerprint-mismatch",
                "tls-certificate-unavailable",
                "tls-not-enabled",
            ),
            SourceSyncTlsReasons.ALL,
        )
        assertEquals("tls-handshake-failed", SourceSyncTlsReasons.HANDSHAKE_FAILED)
        assertEquals("tls-fingerprint-mismatch", SourceSyncTlsReasons.FINGERPRINT_MISMATCH)
        assertEquals("tls-certificate-unavailable", SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE)
        assertEquals("tls-not-enabled", SourceSyncTlsReasons.NOT_ENABLED)
        assertEquals("TLSv1.3", SourceSyncTls.REQUIRED_PROTOCOL)
        assertEquals("mediahub-sync", SourceSyncTls.TARGET_HOST_NAME)
        assertEquals(5, SourceSyncTls.PROBE_BYTES)
    }

    @Test
    fun tlsCodesDoNotOverlapTransferOrAuthCodes() {
        val transfer = SourceSyncTransferReasons.ALL.toSet()
        val auth = SourceSyncTransferAuthReasons.ALL.toSet()
        val tls = SourceSyncTlsReasons.ALL.toSet()
        assertEquals(10, transfer.size)
        assertEquals(5, auth.size)
        assertEquals(4, tls.size)
        assertTrue("传输码与 TLS 码不得相交", (transfer intersect tls).isEmpty())
        assertTrue("认证码与 TLS 码不得相交", (auth intersect tls).isEmpty())
        assertTrue("传输码与认证码不得相交", (transfer intersect auth).isEmpty())
        assertTrue("TLS 码一律 tls- 前缀", tls.all { it.startsWith("tls-") })
    }

    @Test
    fun defaultOptionsAreOffAndSaySo() {
        val off = SourceSyncTlsOptions.Off
        assertFalse(off.enabled)
        assertNull(off.expectedFingerprint)
        assertFalse(off.hasPin)
        assertEquals("SourceSyncTlsOptions { Enabled = false }", off.toString())
    }

    @Test
    fun pinnedOptionsNeverPrintTheFingerprint() {
        val pin = pinOf("mediahub-sync-test")
        val pinned = SourceSyncTlsOptions.Pinned(pin)
        assertTrue(pinned.enabled)
        assertEquals(pin, pinned.expectedFingerprint)
        assertTrue(pinned.hasPin)
        val text = pinned.toString()
        assertEquals("SourceSyncTlsOptions { Enabled = true, ExpectedFingerprint = <redacted> }", text)
        assertFalse("toString 不得含指纹", text.contains(pin))
        assertFalse("toString 不得含指纹片段", text.lowercase().contains(pin.substring(0, 12)))
        assertFalse("toString 不得含密钥相关字样", text.contains("private", ignoreCase = true))
    }

    @Test
    fun pinnedKeepsTheRawTextAndReportsWhetherItParses() {
        val pin = pinOf("mediahub-sync-test")
        val upper = SourceSyncTlsOptions.Pinned(pin.uppercase())
        assertTrue(upper.hasPin)
        assertEquals("原样保存调用方给的字符串", pin.uppercase(), upper.expectedFingerprint)
        assertEquals("规范化成小写 hex", pin, upper.fingerprint?.hex)
        assertFalse(SourceSyncTlsOptions.Pinned("not-a-fingerprint").hasPin)
        assertFalse(SourceSyncTlsOptions.Pinned("").hasPin)
        assertFalse(SourceSyncTlsOptions.Pinned(pin.dropLast(1)).hasPin)
        assertFalse(SourceSyncTlsOptions.pinned(null).hasPin)
        assertFalse(SourceSyncTlsOptions.pinned("   ").hasPin)
        assertTrue(SourceSyncTlsOptions.pinned(pin).hasPin)
    }

    // ---------------------------------------------------------------- B. 指纹与嗅探

    @Test
    fun fingerprintCoversThePublicKeyNotTheWholeCertificate() {
        val store = keyStoreOf("mediahub-sync-test")
        val alias = store.aliases().nextElement()
        val certificate = store.getCertificate(alias) as X509Certificate
        val expected = pinOf("mediahub-sync-test")
        assertEquals("Windows 权威实现算出的 SPKI 指纹", expected, SourceSyncTls.spkiFingerprint(certificate))
        assertNotEquals(
            "指纹不得覆盖整张证书（那是 certificate.encoded）",
            sha256(certificate.encoded),
            SourceSyncTls.spkiFingerprint(certificate),
        )
        assertEquals(64, SourceSyncTls.spkiFingerprint(certificate)?.length)
        assertNull(SourceSyncTls.spkiFingerprint(null))
        assertNull(SourceSyncTls.spkiFingerprintOrNull(null))
    }

    @Test
    fun looksLikeTlsHandshakeOnlyAcceptsRecordHeaders() {
        assertTrue(SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x00)))
        assertTrue(SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x01)))
        assertTrue(SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x02)))
        assertTrue(SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x03)))
        assertTrue(SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x04, 0x00, 0x2F)))
        assertFalse("minor=5 不是握手记录", SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03, 0x05)))
        assertFalse("0x15 是 alert", SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x15, 0x03, 0x01)))
        assertFalse("major 必须是 3", SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x02, 0x01)))
        assertFalse("不足 3 字节", SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x16, 0x03)))
        assertFalse("明文帧头", SourceSyncTls.looksLikeTlsHandshake(byteArrayOf(0x00, 0x00, 0x00, 0x09)))
        assertFalse(SourceSyncTls.looksLikeTlsHandshake(ByteArray(0)))
    }

    @Test
    fun probeRefusesTlsRecordsWithNotEnabled() {
        val clientHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2F)
        val probe = SourceSyncTls.probePlaintext(ByteArrayInputStream(clientHello))
        assertTrue("期望被拒，实际 " + probe, probe is SourceSyncTlsProbe.Refused)
        val refused = probe as SourceSyncTlsProbe.Refused
        assertEquals(SourceSyncTlsReasons.NOT_ENABLED, refused.reason)
        assertTrue(refused.detail.orEmpty().contains("明文"))
    }

    @Test
    fun probeReplaysPlaintextBytesExactly() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x09, 0x02, 0x00, 0x00, 0x00, 0x00, 0x01)
        val probe = SourceSyncTls.probePlaintext(ByteArrayInputStream(payload))
        assertTrue("期望明文，实际 " + probe, probe is SourceSyncTlsProbe.Plaintext)
        assertArrayEquals(payload, (probe as SourceSyncTlsProbe.Plaintext).stream.readBytes())

        val short = byteArrayOf(0x00, 0x01)
        val shortProbe = SourceSyncTls.probePlaintext(ByteArrayInputStream(short))
        assertArrayEquals(short, (shortProbe as SourceSyncTlsProbe.Plaintext).stream.readBytes())

        val emptyProbe = SourceSyncTls.probePlaintext(ByteArrayInputStream(ByteArray(0)))
        assertArrayEquals(ByteArray(0), (emptyProbe as SourceSyncTlsProbe.Plaintext).stream.readBytes())
    }

    @Test
    fun prefixedStreamIsReadOnlyAndLeavesTheInnerStreamOpen() {
        val inner = ByteArrayInputStream(byteArrayOf(5, 6, 7))
        val stream = SourceSyncTlsPrefixedInputStream(byteArrayOf(1, 2, 3, 4), inner)
        assertEquals(4, stream.remainingPrefixBytes)
        assertEquals(1, stream.read())
        assertEquals(3, stream.remainingPrefixBytes)
        assertEquals("available 要含未吐完的前缀（3 + 内层 3）", 6, stream.available())
        val chunk = ByteArray(4)
        assertEquals("一次只吐剩下的前缀，不跨到内层（InputStream 允许短读）", 3, stream.read(chunk, 0, 4))
        assertArrayEquals(byteArrayOf(2, 3, 4, 0), chunk)
        assertEquals(0, stream.remainingPrefixBytes)
        assertEquals(3, stream.available())
        val tail = ByteArray(4)
        assertEquals(3, stream.read(tail, 0, 4))
        assertArrayEquals(byteArrayOf(5, 6, 7, 0), tail)
        assertEquals(0, stream.read(ByteArray(2), 0, 0))
        stream.close()
        assertEquals("close 不关内层流（字节已被读完）", 0, inner.available())
        assertEquals(-1, stream.read())
    }

    @Test
    fun identityFromKeyStoreExposesTheWindowsComputedFingerprint() {
        val identity = identityOf("mediahub-sync-test")
        val pin = pinOf("mediahub-sync-test")
        assertTrue(identity.isUsable)
        assertEquals(pin, identity.certificateFingerprint)
        assertEquals("SourceSyncTlsIdentity(" + pin + ")", identity.toString())

        assertFalse(SourceSyncTlsIdentity.NONE.isUsable)
        assertNull(SourceSyncTlsIdentity.NONE.certificateFingerprint)
        assertEquals("SourceSyncTlsIdentity(<none>)", SourceSyncTlsIdentity.NONE.toString())

        val empty = KeyStore.getInstance("PKCS12")
        empty.load(null, password.toCharArray())
        assertFalse(SourceSyncTlsIdentity.fromKeyStore(empty, password.toCharArray()).isUsable)
    }

    // ---------------------------------------------------------------- C. 默认关闭

    @Test
    fun offKeepsTheFrozenVectorFilesByteIdentical() {
        assertEquals(
            "windows-transfer-vectors.json（LF 归一后）不许变",
            "bd7faa644dcc14a5a7bb4f9590299868cb536cd6b9f4316f91ca0f1ee9946849",
            sha256(lfNormalized(resourceBytes("/sync/windows-transfer-vectors.json"))),
        )
        assertEquals(
            "windows-auth-vectors.json（LF 归一后）不许变",
            "2031a83fc23d1e7c54698d0a86179bee7baf64258bcdc099657d9a09c4d12b57",
            sha256(lfNormalized(resourceBytes("/sync/windows-auth-vectors.json"))),
        )
    }

    @Test
    fun offKeepsThePlaintextPathIdentical() {
        val listener = plainListener()
        val port = listener.localPort
        val envelope = goldenEnvelope()
        val frames = SourceSyncTransfer.encode(envelope, "plain-challenge-01")
        val peerSaw = AtomicReference(-2)
        val peer = serve(listener) { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().write(frames)
            socket.getOutputStream().flush()
            peerSaw.set(
                try {
                    socket.getInputStream().read(ByteArray(64))
                } catch (error: IOException) {
                    -1
                },
            )
        }
        val result = SourceSyncTransferClient().receiveFrom("127.0.0.1", port, "plain-challenge-01")
        peer.await()
        assertTrue("期望成功，实际 " + result, result is SourceSyncTransferResult.Ok)
        val ok = result as SourceSyncTransferResult.Ok
        assertArrayEquals(envelope, ok.envelope)
        assertEquals(2, ok.frameCount)
        assertEquals("明文路径关闭行为不变：对端应读到 EOF", -1, peerSaw.get())
    }

    // ---------------------------------------------------------------- D. 真 TLS 握手

    @Test
    fun pinnedHandshakeReceivesTheGoldenEnvelopeOverRealTls13() {
        val listener = tlsListener(serverContext("mediahub-sync-test"))
        val port = listener.localPort
        val envelope = goldenEnvelope()
        val frames = SourceSyncTransfer.encode(envelope, "tls-challenge-01")
        val peerSaw = AtomicReference(-2)
        val peer = serve(listener) { socket ->
            val tls = socket as SSLSocket
            tls.soTimeout = 20_000
            tls.outputStream.write(frames)
            tls.outputStream.flush()
            peerSaw.set(
                try {
                    tls.inputStream.read(ByteArray(256))
                } catch (error: IOException) {
                    -1
                },
            )
        }
        val pin = pinOf("mediahub-sync-test")
        val result = SourceSyncTransferClient()
            .receiveFrom("127.0.0.1", port, "tls-challenge-01", SourceSyncTlsOptions.Pinned(pin))
        peer.await()
        assertTrue("期望成功，实际 " + result, result is SourceSyncTransferResult.Ok)
        val ok = result as SourceSyncTransferResult.Ok
        assertArrayEquals(envelope, ok.envelope)
        assertEquals(2, ok.frameCount)
        assertEquals("TLS 路径要 graceful 关闭：对端应读到 EOF 而不是被 RST", -1, peerSaw.get())
    }

    @Test
    fun wrongPinFailsWithFingerprintMismatchAndNoApplicationByte() {
        val listener = tlsListener(serverContext("mediahub-sync-test"))
        val port = listener.localPort
        val applicationRead = AtomicInteger(-2)
        val peer = serveTolerantly(listener) { socket ->
            val tls = socket as SSLSocket
            tls.soTimeout = 20_000
            try {
                tls.startHandshake()
            } catch (error: IOException) {
                // 客户端在指纹比对后就会关，握手可能收不到 close_notify。
            }
            applicationRead.set(
                try {
                    tls.inputStream.read(ByteArray(64))
                } catch (error: IOException) {
                    -1
                },
            )
        }
        val result = SourceSyncTransferClient().receiveFrom(
            "127.0.0.1",
            port,
            null,
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-other")),
        )
        peer.await()
        assertTrue("期望指纹不符，实际 " + result, result is SourceSyncTransferResult.Failed)
        val failed = result as SourceSyncTransferResult.Failed
        assertEquals(SourceSyncTlsReasons.FINGERPRINT_MISMATCH, failed.reason)
        assertTrue(
            "诊断应说明一个信封字节都没有读写，实际 " + failed.message,
            failed.message.orEmpty().contains("一个信封字节"),
        )
        assertTrue(
            "对端不该收到任何应用字节，实际读到 " + applicationRead.get(),
            applicationRead.get() <= 0,
        )
    }

    @Test
    fun unusablePinFailsWithCertificateUnavailableBeforeDialing() {
        val socket = Socket()
        val result = SourceSyncTls.handshakeClient(
            socket,
            SourceSyncTlsOptions.Pinned("not-a-fingerprint"),
            300,
        )
        assertTrue("期望 certificate-unavailable，实际 " + result, result is SourceSyncTlsResult.Failed)
        assertEquals(
            SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
            (result as SourceSyncTlsResult.Failed).reason,
        )
        assertFalse("指纹不可用时不得拨号", socket.isConnected)
        socket.close()

        val blankSocket = Socket()
        val blank = SourceSyncTls.handshakeClient(blankSocket, SourceSyncTlsOptions.pinned(null), 300)
        assertEquals(
            SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
            (blank as SourceSyncTlsResult.Failed).reason,
        )
        assertFalse(blankSocket.isConnected)
        blankSocket.close()
    }

    @Test
    fun tlsClientAgainstPlaintextEndpointIsRefusedWithNotEnabled() {
        val listener = plainListener()
        val port = listener.localPort
        val probe = AtomicReference<SourceSyncTlsProbe?>(null)
        val peer = serveTolerantly(listener) { socket ->
            socket.soTimeout = 20_000
            probe.set(SourceSyncTls.probePlaintext(socket.getInputStream()))
        }
        val client = startPeer("tls-client", tolerateFailures = true) {
            val tls = trustAllContext().socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            tls.enabledProtocols = arrayOf("TLSv1.3")
            try {
                tls.startHandshake()
            } catch (error: IOException) {
                // 服务端在嗅探后立刻关连接，握手当然谈不成——这正是本用例要的。
            } finally {
                runCatching { tls.close() }
            }
        }
        client.await()
        peer.await()
        val refused = probe.get()
        assertTrue("期望被拒，实际 " + refused, refused is SourceSyncTlsProbe.Refused)
        assertEquals(
            SourceSyncTlsReasons.NOT_ENABLED,
            (refused as SourceSyncTlsProbe.Refused).reason,
        )
    }

    @Test
    fun plaintextClientAgainstTlsEndpointFailsWithHandshakeFailed() {
        val listener = plainListener()
        val port = listener.localPort
        val outcome = AtomicReference<SourceSyncTlsResult?>(null)
        val identity = identityOf("mediahub-sync-test")
        val peer = serveTolerantly(listener) { socket ->
            socket.soTimeout = 20_000
            outcome.set(SourceSyncTls.handshakeServer(socket, identity))
        }
        val client = startPeer("plaintext-client", tolerateFailures = true) {
            val plain = Socket("127.0.0.1", port)
            plain.use {
                it.getOutputStream().write(SourceSyncTransfer.encode(goldenEnvelope(), "plain-frame"))
                it.getOutputStream().flush()
                it.soTimeout = 5_000
                runCatching { it.getInputStream().read(ByteArray(16)) }
            }
        }
        client.await()
        peer.await()
        val failed = outcome.get()
        assertTrue("期望握手失败，实际 " + failed, failed is SourceSyncTlsResult.Failed)
        assertEquals(
            SourceSyncTlsReasons.HANDSHAKE_FAILED,
            (failed as SourceSyncTlsResult.Failed).reason,
        )
    }

    @Test
    fun tls12OnlyEndpointIsRejectedAsHandshakeFailed() {
        val listener = tlsListener(serverContext("mediahub-sync-test", protocol = "TLS"), arrayOf("TLSv1.2"))
        val port = listener.localPort
        val peer = serveTolerantly(listener) { socket ->
            val tls = socket as SSLSocket
            tls.soTimeout = 20_000
            runCatching { tls.startHandshake() }
        }
        val result = SourceSyncTransferClient().receiveFrom(
            "127.0.0.1",
            port,
            null,
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-test")),
        )
        peer.await()
        assertTrue("期望握手失败，实际 " + result, result is SourceSyncTransferResult.Failed)
        val failed = result as SourceSyncTransferResult.Failed
        assertEquals(SourceSyncTlsReasons.HANDSHAKE_FAILED, failed.reason)
        assertNotEquals(
            "协议版本问题不能被折成业务超时",
            SourceSyncTransferReasons.TRANSFER_TIMEOUT,
            failed.reason,
        )
    }

    @Test
    fun handshakeTimeoutIsReportedSeparatelyFromTheReadTimeout() {
        val listener = plainListener()
        val port = listener.localPort
        val peer = serveTolerantly(listener) { socket ->
            socket.soTimeout = 20_000
            Thread.sleep(1_500)
        }
        val plain = Socket("127.0.0.1", port)
        plain.soTimeout = 10_000
        val started = System.currentTimeMillis()
        val result = SourceSyncTls.handshakeClient(
            plain,
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-test")),
            300,
        )
        val elapsed = System.currentTimeMillis() - started
        runCatching { plain.close() }
        peer.await()
        assertTrue("期望握手失败，实际 " + result, result is SourceSyncTlsResult.Failed)
        val failed = result as SourceSyncTlsResult.Failed
        assertEquals(SourceSyncTlsReasons.HANDSHAKE_FAILED, failed.reason)
        assertTrue("诊断要带握手预算，实际 " + failed.detail, failed.detail.orEmpty().contains("300"))
        assertTrue("不该等到业务读取超时，实际耗时 " + elapsed + " 毫秒", elapsed < 1_500)
    }

    @Test
    fun wrongPinIsNotTrustedSoTheNextAttemptCanSucceed() {
        val context = serverContext("mediahub-sync-test")
        val listener = tlsListener(context)
        val port = listener.localPort
        val frames = SourceSyncTransfer.encode(goldenEnvelope(), "retry-challenge")
        val peer = startPeer("two-connections", tolerateFailures = true) {
            listener.use { server ->
                val first = server.accept() as SSLSocket
                first.soTimeout = 20_000
                runCatching { first.startHandshake() }
                runCatching { first.inputStream.read(ByteArray(16)) }
                runCatching { first.close() }

                val second = server.accept() as SSLSocket
                second.soTimeout = 20_000
                second.outputStream.write(frames)
                second.outputStream.flush()
                runCatching { second.inputStream.read(ByteArray(16)) }
                runCatching { second.close() }
            }
        }
        val client = SourceSyncTransferClient()
        val first = client.receiveFrom(
            "127.0.0.1",
            port,
            "retry-challenge",
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-other")),
        )
        assertTrue("第一次必须失败，实际 " + first, first is SourceSyncTransferResult.Failed)
        assertEquals(
            SourceSyncTlsReasons.FINGERPRINT_MISMATCH,
            (first as SourceSyncTransferResult.Failed).reason,
        )
        val second = client.receiveFrom(
            "127.0.0.1",
            port,
            "retry-challenge",
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-test")),
        )
        peer.await()
        assertTrue("错一次不代表下次永远错：换对指纹必须成功，实际 " + second, second is SourceSyncTransferResult.Ok)
        assertArrayEquals(goldenEnvelope(), (second as SourceSyncTransferResult.Ok).envelope)
    }

    @Test
    fun gracefulCloseLetsThePeerReachEofWithoutReset() {
        val identity = identityOf("mediahub-sync-test")
        val pin = pinOf("mediahub-sync-test")
        var iteration = 0
        while (iteration < 5) {
            val listener = plainListener()
            val port = listener.localPort
            val eof = AtomicReference(-2)
            val peer = serve(listener) { socket ->
                socket.soTimeout = 20_000
                val outcome = SourceSyncTls.handshakeServer(socket, identity)
                assertTrue("服务端握手应成功，实际 " + outcome, outcome is SourceSyncTlsResult.Ok)
                val session = (outcome as SourceSyncTlsResult.Ok).session
                session.output.write(byteArrayOf(0x41, 0x42))
                session.output.flush()
                eof.set(
                    try {
                        session.input.read(ByteArray(16))
                    } catch (error: IOException) {
                        -3
                    },
                )
            }
            val client = Socket("127.0.0.1", port)
            client.soTimeout = 20_000
            val outcome = SourceSyncTls.handshakeClient(client, SourceSyncTlsOptions.Pinned(pin), 10_000)
            assertTrue("客户端握手应成功，实际 " + outcome, outcome is SourceSyncTlsResult.Ok)
            val session = (outcome as SourceSyncTlsResult.Ok).session
            assertEquals(0x41, session.input.read())
            assertEquals(0x42, session.input.read())
            SourceSyncTls.gracefulClose(session.socket)
            peer.await()
            assertEquals(
                "第 " + iteration + " 轮应读到干净 EOF（不是 RST），实际 " + eof.get(),
                -1,
                eof.get(),
            )
            iteration += 1
        }
    }

    @Test
    fun tlsAndAuthenticationCompose() {
        val secret = "tls-and-tag-secret".toByteArray(Charsets.UTF_8)
        val challenge = "tls-auth-challenge"
        val envelope = goldenEnvelope()
        val buffer = ByteArrayOutputStream()
        val sent = SourceSyncTransfer.sendTo(
            buffer,
            envelope,
            challenge,
            SourceSyncTransferOptions.withSecret(secret),
        )
        assertTrue("打帧应成功，实际 " + sent, sent is SourceSyncTransferResult.Ok)
        val frames = buffer.toByteArray()

        val listener = tlsListener(serverContext("mediahub-sync-test"))
        val port = listener.localPort
        val peer = serve(listener) { socket ->
            val tls = socket as SSLSocket
            tls.soTimeout = 20_000
            tls.outputStream.write(frames)
            tls.outputStream.flush()
            runCatching { tls.inputStream.read(ByteArray(16)) }
        }
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 20_000
        val outcome = SourceSyncTls.handshakeClient(
            client,
            SourceSyncTlsOptions.Pinned(pinOf("mediahub-sync-test")),
            10_000,
        )
        assertTrue("客户端握手应成功，实际 " + outcome, outcome is SourceSyncTlsResult.Ok)
        val session = (outcome as SourceSyncTlsResult.Ok).session
        val received = SourceSyncTransfer.receive(
            session.input,
            challenge,
            SourceSyncTransferOptions.withSecret(secret),
        )
        SourceSyncTls.gracefulClose(session.socket)
        peer.await()
        assertTrue("期望成功，实际 " + received, received is SourceSyncTransferResult.Ok)
        val ok = received as SourceSyncTransferResult.Ok
        assertArrayEquals(envelope, ok.envelope)
        assertTrue("挑战帧 + 数据块 + tag 帧，至少 3 帧，实际 " + ok.frameCount, ok.frameCount >= 3)
    }
}
