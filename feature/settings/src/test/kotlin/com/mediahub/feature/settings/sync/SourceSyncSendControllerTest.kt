package com.mediahub.feature.settings.sync

import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncReceipt
import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncReceiptReasons
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.SourceSyncTransfer
import com.mediahub.core.common.sync.SourceSyncTransferAuthReasons
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.core.common.sync.SourceSyncTransferResult
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import java.io.Closeable
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-42 发送控制器的 JVM 单测。
 *
 * 纪律：
 * - 协议一律真跑：明文与 TLS 两条路径都由本文件里的回环 ServerSocket 真握手、真收帧，
 *   不用假传输替代（假端口只用于「还没拨号就该拒」与单飞 / 取消这类时序用例）；
 * - 口令与指纹不出门：任何断言都不把口令写进失败信息；
 * - TLS 夹具从 core/common 同源复制（sha256 与 Windows 侧算出的 SPKI 指纹在
 *   fixturePinsMatchTheFrozenWindowsVectors 里钉死，防夹具漂移）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncSendControllerTest {

    @Test
    fun defaultsAreIdleAndSafe() {
        val controller = SourceSyncSendController(exportPort(), FakeSendPort())
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertNull(SourceSyncSendCopy.lineOf(SourceSyncSendState.Idle))
        assertNull(SourceSyncSendCopy.codeOf(SourceSyncSendState.Idle))
        assertFalse(controller.isSending)

        val form = SourceSyncSendForm()
        assertEquals("127.0.0.1", form.hostOrLoopback)
        assertNull(form.portNumber)
        assertFalse(form.canSubmit)
        assertFalse(form.tls)
        assertFalse(form.auth)
        assertFalse(form.receipt)
        assertTrue(SourceSyncSendCopy.TITLE.contains("发送"))
    }

    @Test
    fun invalidPortsNeverReachTheTransport() = runTest {
        val ports = listOf("", "0", "65536", "-1", "abc", "9000.5", " 0x10", "   ")
        for (text in ports) {
            val export = exportPort()
            val send = FakeSendPort()
            val controller = SourceSyncSendController(export, send)
            val accepted = controller.send(form(port = text))
            assertFalse("端口 [" + text + "] 不该被接受", accepted)
            assertEquals(
                SourceSyncSendController.PORT_INVALID,
                SourceSyncSendCopy.codeOf(controller.state.value),
            )
            assertEquals(0, export.calls)
            assertEquals(0, send.calls)
        }
    }

    @Test
    fun boundaryPortsAreAccepted() = runTest {
        for (text in listOf("1", "65535")) {
            val send = FakeSendPort()
            val controller = SourceSyncSendController(exportPort(), send)
            assertTrue(controller.send(form(port = text)))
            assertTrue("端口：" + send.port, send.port == text.toInt())
            assertTrue(controller.state.value is SourceSyncSendState.Sent)
        }
    }

    @Test
    fun nonLoopbackHostIsRejectedBeforeDialing() = runTest {
        val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
        assertTrue(controller.send(form(host = "10.1.2.3", port = "9")))
        val state = controller.state.value
        assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, (state as SourceSyncSendState.Failed).code)
    }

    @Test
    fun realLoopbackDeliveryWritesTheEnvelopeJson() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder)
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString())))
            peer.await()
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Sent)
            val payload = envelopeJson().toByteArray(Charsets.UTF_8)
            assertArrayEquals(payload, recorder.envelope.get()!!)
            assertEquals(SourceSyncTransfer.encode(payload).size, recorder.bytes.get())
            assertEquals(SourceSyncTransfer.chunkCount(payload.size), recorder.frames.get())
            assertEquals(payload.size, (state as SourceSyncSendState.Sent).bytes)
        } finally {
            peer.close()
        }
    }

    @Test
    fun sentStateCarriesBytesFramesAndSourceCount() = runTest {
        val export = FakeExportPort(
            SourceSyncSendExport(envelopeJson = envelopeJson(), sourceCount = 2, skippedCount = 1),
        )
        val send = FakeSendPort(SourceSyncTransferResult.Ok(ByteArray(0), 3, null))
        val controller = SourceSyncSendController(export, send)
        assertTrue(controller.send(form()))
        val sent = controller.state.value as SourceSyncSendState.Sent
        assertEquals(envelopeJson().toByteArray(Charsets.UTF_8).size, sent.bytes)
        assertEquals(3, sent.frames)
        assertEquals(2, sent.sourceCount)
        assertEquals(1, sent.skippedCount)
        assertFalse(sent.receipt)
        val line = SourceSyncSendCopy.lineOf(sent)
        assertTrue(line!!.contains("已发送"))
        assertTrue(line.contains("跳过 1 条"))
        assertTrue(line.contains("不代表对端接受"))
        assertNull(SourceSyncSendCopy.codeOf(sent))
    }

    @Test
    fun plainSendWithoutReceiptSaysSoInTheLine() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder)
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString())))
            peer.await()
            val sent = controller.state.value as SourceSyncSendState.Sent
            assertFalse(sent.receipt)
            assertFalse(SourceSyncSendCopy.lineOf(sent)!!.contains("0x04 回执"))
        } finally {
            peer.close()
        }
    }

    @Test
    fun receiptConfirmedWhenThePeerAcks() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder) { socket, envelope ->
            SourceSyncReceipt.acknowledge(
                socket.getOutputStream(),
                envelope,
                SourceSyncReceiptOptions.writing(),
            )
        }
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString(), receipt = true)))
            peer.await()
            val sent = controller.state.value as SourceSyncSendState.Sent
            assertTrue(sent.receipt)
            assertTrue(SourceSyncSendCopy.lineOf(sent)!!.contains("0x04 回执"))
        } finally {
            peer.close()
        }
    }

    @Test
    fun receiptMissingWhenThePeerClosesWithoutAnswering() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder)
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString(), receipt = true)))
            peer.await()
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
            assertEquals(
                SourceSyncReceiptReasons.RECEIPT_MISSING,
                (state as SourceSyncSendState.Failed).code,
            )
        } finally {
            peer.close()
        }
    }

    @Test
    fun receiptTimeoutWhenThePeerStaysSilent() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder) { _, _ ->
            // 收下信封之后既不回执也不关连接：发送端的回执预算（默认 2000 ms）先到期。
            Thread.sleep(2_500L)
        }
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString(), receipt = true)))
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
            assertEquals(
                SourceSyncReceiptReasons.RECEIPT_TIMEOUT,
                (state as SourceSyncSendState.Failed).code,
            )
        } finally {
            peer.close()
            peer.awaitOrIgnoreFailure()
        }
    }

    @Test
    fun receiptInvalidWhenThePeerAnswersAnotherByte() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder) { socket, _ ->
            socket.getOutputStream().write(0x02)
            socket.getOutputStream().flush()
        }
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(controller.send(form(port = peer.port.toString(), receipt = true)))
            peer.await()
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
            assertEquals(
                SourceSyncReceiptReasons.RECEIPT_INVALID,
                (state as SourceSyncSendState.Failed).code,
            )
        } finally {
            peer.close()
        }
    }

    @Test
    fun tlsPinnedDeliveryOverRealTls13() = runTest {
        val recorder = Recorder()
        val peer = tlsPeer(MAIN_FIXTURE, recorder) { socket, envelope ->
            SourceSyncReceipt.acknowledge(
                socket.getOutputStream(),
                envelope,
                SourceSyncReceiptOptions.writing(),
            )
        }
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(
                controller.send(
                    form(port = peer.port.toString(), tls = true, fingerprint = MAIN_PIN, receipt = true),
                ),
            )
            peer.await()
            assertArrayEquals(envelopeJson().toByteArray(Charsets.UTF_8), recorder.envelope.get()!!)
            val sent = controller.state.value as SourceSyncSendState.Sent
            assertTrue(sent.receipt)
        } finally {
            peer.close()
        }
    }

    @Test
    fun wrongPinIsRefusedBeforeAnyEnvelopeByte() = runTest {
        val recorder = Recorder()
        // 服务端用的是另一张证书：客户端钉的是 MAIN_PIN，握手完成后立刻比指纹并断开。
        val peer = tlsPeer(OTHER_FIXTURE, recorder)
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(
                controller.send(form(port = peer.port.toString(), tls = true, fingerprint = MAIN_PIN)),
            )
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
            assertEquals(
                SourceSyncTlsReasons.FINGERPRINT_MISMATCH,
                (state as SourceSyncSendState.Failed).code,
            )
            peer.awaitOrIgnoreFailure()
            assertEquals(0, recorder.bytes.get())
            assertNull(recorder.envelope.get())
        } finally {
            peer.close()
        }
    }

    @Test
    fun unusableFingerprintIsRejectedBeforeExporting() = runTest {
        val export = exportPort()
        val send = FakeSendPort()
        val controller = SourceSyncSendController(export, send)
        assertFalse(controller.send(form(tls = true, fingerprint = "not-a-fingerprint")))
        assertEquals(
            SourceSyncSendController.FINGERPRINT_INVALID,
            SourceSyncSendCopy.codeOf(controller.state.value),
        )
        assertEquals(0, export.calls)
        assertEquals(0, send.calls)
    }

    @Test
    fun tlsWithoutFingerprintIsAlsoRejected() = runTest {
        val export = exportPort()
        val send = FakeSendPort()
        val controller = SourceSyncSendController(export, send)
        assertFalse(controller.send(form(tls = true, fingerprint = "   ")))
        assertEquals(
            SourceSyncSendController.FINGERPRINT_INVALID,
            SourceSyncSendCopy.codeOf(controller.state.value),
        )
        assertEquals(0, send.calls)
    }

    @Test
    fun authMismatchIsSeenByThePeerNotByTheSender() = runTest {
        val recorder = Recorder()
        val peerSecret = "peer-side-secret".toByteArray(Charsets.UTF_8)
        val peer = plainPeer(recorder, SourceSyncTransferOptions.withSecret(peerSecret))
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(
                controller.send(
                    form(
                        port = peer.port.toString(),
                        auth = true,
                        secret = "sender-side-secret",
                        challenge = "nonce-w642",
                    ),
                ),
            )
            peer.await()
            assertEquals(SourceSyncTransferAuthReasons.AUTH_MISMATCH, recorder.reason.get())
            assertNull(recorder.envelope.get())
            // 发送端只证明字节写完了：对端拒收时它看不到原因（W6-35 case R6a）。
            assertTrue(controller.state.value is SourceSyncSendState.Sent)
        } finally {
            peer.close()
        }
    }

    @Test
    fun authenticatedDeliveryAddsTheTagFrame() = runTest {
        val recorder = Recorder()
        val shared = "w6-42-interop-secret"
        val peer = plainPeer(recorder, SourceSyncTransferOptions.withSecret(shared.toByteArray(Charsets.UTF_8)))
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(
                controller.send(
                    form(
                        port = peer.port.toString(),
                        auth = true,
                        secret = shared,
                        challenge = "  nonce-w642  ",
                    ),
                ),
            )
            peer.await()
            val payload = envelopeJson().toByteArray(Charsets.UTF_8)
            assertArrayEquals(payload, recorder.envelope.get()!!)
            assertEquals(SourceSyncTransfer.chunkCount(payload.size) + 2, recorder.frames.get())
            val sent = controller.state.value as SourceSyncSendState.Sent
            assertEquals(SourceSyncTransfer.chunkCount(payload.size) + 2, sent.frames)
        } finally {
            peer.close()
        }
    }

    @Test
    fun authWithoutChallengeIsRefusedByTheEngineBeforeWriting() = runTest {
        val recorder = Recorder()
        val peer = plainPeer(recorder)
        try {
            val controller = SourceSyncSendController(exportPort(), SourceSyncTransferSendPort())
            assertTrue(
                controller.send(
                    form(port = peer.port.toString(), auth = true, secret = "secret-without-nonce", challenge = "   "),
                ),
            )
            peer.await()
            val state = controller.state.value
            assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
            assertEquals(SourceSyncTransferAuthReasons.AUTH_REQUIRED, (state as SourceSyncSendState.Failed).code)
            assertEquals(0, recorder.bytes.get())
        } finally {
            peer.close()
        }
    }

    @Test
    fun secretNeverAppearsInToStringOrState() = runTest {
        val secret = "super-secret-w642-do-not-print"
        val send = FakeSendPort(
            SourceSyncTransferResult.Failed(SourceSyncTransferReasons.CONNECT_FAILED, "拒绝连接"),
        )
        val controller = SourceSyncSendController(exportPort(), send)
        val filled = form(auth = true, secret = secret)
        assertFalse(filled.toString().contains(secret))
        assertTrue(filled.toString().contains("<redacted>"))

        assertTrue(controller.send(filled))
        assertFalse(controller.state.value.toString().contains(secret))
        assertFalse(SourceSyncSendCopy.lineOf(controller.state.value)!!.contains(secret))
        // 口令确实交给了传输端口（只在这一层用一次），但只存在于 options 里。
        assertArrayEquals(secret.toByteArray(Charsets.UTF_8), send.auth!!.secret)
    }

    @Test
    fun duplicateTapSendsOnlyOne() = runTest {
        val send = FakeSendPort(gate = CompletableDeferred())
        val controller = SourceSyncSendController(exportPort(), send)
        val first = launch { controller.send(form()) }
        runCurrent()
        assertTrue(controller.isSending)
        assertFalse(controller.send(form()))
        assertEquals(SourceSyncSendState.Sending, controller.state.value)
        send.gate!!.complete(Unit)
        first.join()
        assertEquals(1, send.calls)
        assertTrue(controller.state.value is SourceSyncSendState.Sent)
        assertFalse(controller.isSending)
    }

    @Test
    fun exportFailureIsReportedWithoutDialing() = runTest {
        val send = FakeSendPort()
        val controller = SourceSyncSendController(
            exportPort(failure = IllegalStateException("db locked")),
            send,
        )
        assertTrue(controller.send(form()))
        val state = controller.state.value
        assertTrue("实际：" + state, state is SourceSyncSendState.Failed)
        assertEquals(SourceSyncSendController.EXPORT_FAILED, (state as SourceSyncSendState.Failed).code)
        assertEquals("IllegalStateException", state.detail)
        assertEquals(0, send.calls)
    }

    @Test
    fun successAfterFailureCarriesNoStaleCode() = runTest {
        val send = FakeSendPort(
            SourceSyncTransferResult.Failed(SourceSyncTransferReasons.CONNECT_FAILED, "拒绝连接"),
        )
        val controller = SourceSyncSendController(exportPort(), send)
        assertTrue(controller.send(form()))
        assertEquals(
            SourceSyncTransferReasons.CONNECT_FAILED,
            SourceSyncSendCopy.codeOf(controller.state.value),
        )
        send.result = SourceSyncTransferResult.Ok(ByteArray(0), 1, null)
        assertTrue(controller.send(form()))
        assertNull(SourceSyncSendCopy.codeOf(controller.state.value))
        assertTrue(controller.state.value is SourceSyncSendState.Sent)
    }

    @Test
    fun clearResultDropsThePreviousResult() = runTest {
        val controller = SourceSyncSendController(exportPort(), FakeSendPort())
        assertTrue(controller.send(form()))
        assertTrue(controller.state.value is SourceSyncSendState.Sent)
        controller.clearResult()
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertNull(SourceSyncSendCopy.lineOf(controller.state.value))
    }

    @Test
    fun cancelledSendGoesBackToIdle() = runTest {
        val send = FakeSendPort(gate = CompletableDeferred())
        val controller = SourceSyncSendController(exportPort(), send)
        val job = launch { controller.send(form()) }
        runCurrent()
        assertTrue(controller.isSending)
        job.cancel()
        job.join()
        assertEquals(SourceSyncSendState.Idle, controller.state.value)
        assertFalse(controller.isSending)
        send.gate!!.complete(Unit)
        send.gate = null
        assertTrue(controller.send(form()))
        assertTrue(controller.state.value is SourceSyncSendState.Sent)
    }

    @Test
    fun challengeIsTrimmedAndForwarded() = runTest {
        val send = FakeSendPort()
        val controller = SourceSyncSendController(exportPort(), send)
        assertTrue(controller.send(form(challenge = "  nonce-w642  ")))
        assertEquals("nonce-w642", send.challenge)
        assertEquals("127.0.0.1", send.host)
        assertEquals(SourceSyncReceiptOptions.Off, send.receipt)
        assertNull(send.auth)
        assertEquals(SourceSyncTlsOptions.Off, send.tls)
        assertArrayEquals(envelopeJson().toByteArray(Charsets.UTF_8), send.envelope!!)
    }

    @Test
    fun fixturePinsMatchTheFrozenWindowsVectors() {
        assertEquals(MAIN_P12_SHA256, sha256OfResource("/tls/mediahub-sync-test.p12"))
        assertEquals(OTHER_P12_SHA256, sha256OfResource("/tls/mediahub-sync-other.p12"))
        assertEquals(MAIN_PIN, serverPin(MAIN_FIXTURE))
        assertEquals(OTHER_PIN, serverPin(OTHER_FIXTURE))
        assertEquals(64, MAIN_PIN.length)
        val parsed = PairingFingerprint.tryParse(MAIN_PIN)
        assertTrue(parsed != null)
        assertEquals(MAIN_PIN, parsed!!.hex)
    }

    // ---- 夹具 ----

    private fun form(
        host: String = SourceSyncSendForm.DEFAULT_HOST,
        port: String = "9000",
        challenge: String = "",
        tls: Boolean = false,
        fingerprint: String = "",
        auth: Boolean = false,
        secret: String = "",
        receipt: Boolean = false,
    ): SourceSyncSendForm = SourceSyncSendForm(
        host = host,
        port = port,
        challenge = challenge,
        tls = tls,
        fingerprint = fingerprint,
        auth = auth,
        secret = secret,
        receipt = receipt,
    )

    /** 两来源信封：与 Windows transfer serve 的 apply 计数口径一致（emby + jellyfin，无墓碑）。 */
    private fun envelopeJson(): String = SourceSyncCodec.encode(
        SourceSyncEnvelope(
            deviceId = "device-android",
            snapshotId = "snap-w642",
            createdAtEpochMs = 1_792_800_000_000L,
            capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = listOf("emby", "jellyfin")),
            sources = listOf(
                SourceRecord(
                    syncSourceId = "local-w642-emby",
                    sourceType = SyncSourceTypeValue.parse("emby"),
                    displayName = "客厅 Emby",
                    endpoints = listOf(SourceEndpoint(url = "http://127.0.0.1:8097", isPrimary = true)),
                    recordRevision = 3L,
                ),
                SourceRecord(
                    syncSourceId = "local-w642-jellyfin",
                    sourceType = SyncSourceTypeValue.parse("jellyfin"),
                    displayName = "书房 Jellyfin",
                    endpoints = listOf(SourceEndpoint(url = "http://127.0.0.1:8096", isPrimary = true)),
                    recordRevision = 2L,
                ),
            ),
        ),
    )

    /** 导出端口：默认就是上面那两来源信封，个别用例再覆盖计数或制造失败。 */
    private fun exportPort(
        export: SourceSyncSendExport = SourceSyncSendExport(envelopeJson = envelopeJson(), sourceCount = 2),
        failure: Exception? = null,
    ): FakeExportPort = FakeExportPort(export, failure)

    /** 明文对端：真解帧，收下的信封 / 失败码 / 应用字节数都记下来供断言。 */
    private fun plainPeer(
        recorder: Recorder,
        auth: SourceSyncTransferOptions? = null,
        after: (Socket, ByteArray) -> Unit = { _, _ -> },
    ): Peer = Peer { socket ->
        val counting = CountingInput(socket.getInputStream())
        val result = SourceSyncTransfer.receive(counting, null, auth)
        recorder.bytes.set(counting.count.get())
        when (result) {
            is SourceSyncTransferResult.Ok -> {
                recorder.envelope.set(result.envelope)
                recorder.frames.set(result.frameCount)
                after(socket, result.envelope)
            }
            is SourceSyncTransferResult.Failed -> recorder.reason.set(result.reason)
        }
    }

    /** TLS 对端：用夹具里的 PKCS#12 起真 TLS 1.3 监听，握手由第一次读隐式触发。 */
    private fun tlsPeer(
        fixture: String,
        recorder: Recorder,
        after: (Socket, ByteArray) -> Unit = { _, _ -> },
    ): Peer = Peer(serverContext(fixture)) { socket ->
        val counting = CountingInput(socket.getInputStream())
        val result = SourceSyncTransfer.receive(counting)
        recorder.bytes.set(counting.count.get())
        when (result) {
            is SourceSyncTransferResult.Ok -> {
                recorder.envelope.set(result.envelope)
                recorder.frames.set(result.frameCount)
                after(socket, result.envelope)
            }
            is SourceSyncTransferResult.Failed -> recorder.reason.set(result.reason)
        }
    }

    private class Recorder {
        val envelope = AtomicReference<ByteArray?>(null)
        val reason = AtomicReference<String?>(null)
        /** 对端从流里读到的字节总数（含 4 字节帧长与 9 字节块头，所以大于信封字节）。 */
        val bytes = AtomicInteger(0)
        val frames = AtomicInteger(0)
    }

    private class CountingInput(private val inner: InputStream) : InputStream() {
        val count = AtomicInteger(0)

        override fun read(): Int {
            val value = inner.read()
            if (value >= 0) count.incrementAndGet()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = inner.read(buffer, offset, length)
            if (read > 0) count.addAndGet(read)
            return read
        }
    }

    /** 单连接回环对端（明文或 TLS）；handler 在 daemon 线程里跑，异常记在 failure 上。 */
    private class Peer(
        context: SSLContext? = null,
        private val handler: (Socket) -> Unit,
    ) : Closeable {

        private val server: ServerSocket
        private val failure = AtomicReference<Throwable?>(null)
        private val thread: Thread
        val port: Int

        init {
            val created: ServerSocket = if (context == null) {
                ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            } else {
                val tls = context.serverSocketFactory.createServerSocket(
                    0,
                    8,
                    InetAddress.getLoopbackAddress(),
                ) as SSLServerSocket
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls
            }
            server = created
            port = created.localPort
            thread = Thread({
                try {
                    created.accept().use { socket ->
                        socket.soTimeout = 20_000
                        handler(socket)
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    runCatching { created.close() }
                }
            }, "w6-42-peer")
            thread.isDaemon = true
            thread.start()
        }

        fun await(timeoutMs: Long = 20_000) {
            thread.join(timeoutMs)
            if (thread.isAlive) throw AssertionError("对端线程没有在超时内结束")
            failure.get()?.let { error ->
                val message = StringBuilder("对端线程失败：")
                var current: Throwable? = error
                while (current != null) {
                    message.append("\n  ").append(current.javaClass.name).append(": ").append(current.message)
                    for (frame in current.stackTrace.take(6)) {
                        message.append("\n      at ").append(frame)
                    }
                    current = current.cause
                }
                throw AssertionError(message.toString(), error)
            }
        }

        /** 用于「对端必然看到异常」的用例：只等线程结束，不把对端异常算成失败。 */
        fun awaitOrIgnoreFailure(timeoutMs: Long = 20_000) {
            thread.join(timeoutMs)
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun keyStoreOf(fixture: String): KeyStore {
        val stream = SourceSyncSendControllerTest::class.java.getResourceAsStream("/tls/" + fixture + ".p12")
            ?: throw AssertionError("缺少测试夹具 /tls/" + fixture + ".p12")
        return KeyStore.getInstance("PKCS12").apply {
            stream.use { load(it, FIXTURE_PASSWORD.toCharArray()) }
        }
    }

    private fun serverContext(fixture: String): SSLContext {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStoreOf(fixture), FIXTURE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLSv1.3").apply {
            init(factory.keyManagers, null, null)
        }
    }

    private fun serverPin(fixture: String): String {
        val keyStore = keyStoreOf(fixture)
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (keyStore.isKeyEntry(alias)) {
                val certificate = keyStore.getCertificate(alias) as X509Certificate
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                return hexOf(digest)
            }
        }
        throw AssertionError("夹具里没有私钥条目：" + fixture)
    }

    private fun sha256OfResource(path: String): String {
        val stream = SourceSyncSendControllerTest::class.java.getResourceAsStream(path)
            ?: throw AssertionError("缺少资源 " + path)
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { input ->
            val buffer = ByteArray(4_096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hexOf(digest.digest())
    }

    private fun hexOf(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    private class FakeExportPort(
        var export: SourceSyncSendExport,
        var failure: Exception? = null,
    ) : SourceSyncExportPort {
        var calls = 0

        override suspend fun export(): SourceSyncSendExport {
            calls++
            failure?.let { throw it }
            return export
        }
    }

    private class FakeSendPort(
        var result: SourceSyncTransferResult = SourceSyncTransferResult.Ok(ByteArray(0), 1, null),
        var gate: CompletableDeferred<Unit>? = null,
    ) : SourceSyncSendPort {
        var calls = 0
        var host: String? = null
        var port: Int? = null
        var challenge: String? = null
        var tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off
        var auth: SourceSyncTransferOptions? = null
        var receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off
        var envelope: ByteArray? = null

        override suspend fun send(
            host: String,
            port: Int,
            envelope: ByteArray,
            challenge: String?,
            tls: SourceSyncTlsOptions,
            auth: SourceSyncTransferOptions?,
            receipt: SourceSyncReceiptOptions,
        ): SourceSyncTransferResult {
            calls++
            this.host = host
            this.port = port
            this.envelope = envelope
            this.challenge = challenge
            this.tls = tls
            this.auth = auth
            this.receipt = receipt
            gate?.await()
            return result
        }
    }

    private companion object {
        /** 与 core/common 的 tls-vectors.json 同源：p12 口令、两张证书的 SPKI 指纹与文件摘要。 */
        const val MAIN_FIXTURE: String = "mediahub-sync-test"
        const val OTHER_FIXTURE: String = "mediahub-sync-other"
        const val FIXTURE_PASSWORD: String = "mediahub-tls-test"
        const val MAIN_PIN: String = "2bb12d139178ee3b0f219e409a89c99b51be165914e21189eeb039b2e4dd37ce"
        const val OTHER_PIN: String = "2341fc10318175128d05ca4ab1acb59a764f8b2860670759619f396a478802ac"
        const val MAIN_P12_SHA256: String = "8b7329f862f550a8cd33edb38e5d8b9740825a3ec898984a974581941af14bc9"
        const val OTHER_P12_SHA256: String = "b7db6a49ee5516b46b500e4d44ee6279f19868a19aa2af86f497a224724e4da6"
        const val HEX_DIGITS: String = "0123456789abcdef"
    }
}
