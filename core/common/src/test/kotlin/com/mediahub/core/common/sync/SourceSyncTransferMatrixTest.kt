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
 * W6-57：TLS × 认证 × 回执 × 投递 的四维组合矩阵（JVM 单测，真回环 socket）。
 *
 * 为什么要单独一个套件：四个维度各自都有成套的单测（SourceSyncTlsTest / SourceSyncTransferAuthTest /
 * SourceSyncReceiptTest / SourceSyncDeliveryTest，外加 SourceSyncTransferListenerTest 里的个别组合），
 * 但「几维同时打开时谁说了算」只在少数几例里点到（回执优先于投递、TLS 之后才谈认证）。
 * 本类把矩阵按 20 个格子铺开：每一格都是一次真实回环连接（监听端 SourceSyncTransferListener +
 * 客户端 SourceSyncTransferClient），不 mock socket、不 stub 帧层，断言只落在两个外部可观察面：
 * 客户端拿到的 [SourceSyncTransferResult]（成功 / 稳定码 / 投递确认对象）与监听端拿到的
 * [SourceSyncListenerTransfer]（收到 / 稳定码 / 是否真写了回执）。
 *
 * 与 Windows 侧的口径对照：windows/tools/acceptance/w6/transfer-tls-auth.ps1 用两个真实进程跑同形状的
 * 20 例组合矩阵（W6-45），码表与 Windows 同名同序。差别只在形态：那边是 CLI 子进程 + stderr 文本，
 * 这边是库调用 + 返回值，所以这里能断言得更细（例如「回执开了以后投递确认必须是 null」在 CLI 上只能
 * 从退出码间接看出来）。
 *
 * 一条协议事实（矩阵里反复出现，值得单独写下来）：认证开启时**必须**同时宣布挑战——tag 的 MAC 输入是
 * 「挑战的 UTF-8 字节 ‖ 信封字节」，没有挑战就没有可绑定的 nonce，发送端在本地就报 auth-required
 * 并且一个字节都不写（对端只会看到一条空连接、报 frame-truncated）。所以本类每个开认证的格子都同时给挑战。
 *
 * 纪律：只碰 127.0.0.1。非回环地址一格都不 bind、不拨（否则 Windows 会弹防火墙授权窗，违背
 * 「GUI/自动化测试不得干扰使用者」的约定）；真实两机跨机证据仍是 NOT_RUN。
 */
class SourceSyncTransferMatrixTest {

    private val password: String = "mediahub-tls-test"

    /** 监听端用的那枚夹具（同时提供 p12 与公钥指纹）。 */
    private val receiverFixture: String = "mediahub-sync-test"

    /** 另一枚夹具：只借它的指纹当「错的指纹」，从不拿它起监听。 */
    private val otherFixture: String = "mediahub-sync-other"

    private val secret: ByteArray = "w6-57-matrix-secret".toByteArray(Charsets.UTF_8)

    private val wrongSecret: ByteArray = "w6-57-matrix-wrong".toByteArray(Charsets.UTF_8)

    private val challenge: String = "w6-57-challenge"

    private val receiptBudgetMs: Int = 2_000

    private val deliveryBudgetMs: Int = 2_000

    // ------------------------------------------------------------------ 一维与基线

    /** 格 1：四维全关。帧流、帧数与 W6-22 起的明文传输完全一致。 */
    @Test
    fun cell01_nothingEnabledIsThePlainBaseline() {
        val outcome = exchange()
        val sent = assertSentOk(outcome.client)
        val received = assertReceived(outcome.listener)
        assertArrayEquals(goldenEnvelope(), received.envelope)
        assertEquals(SourceSyncTransfer.chunkCount(received.bytes), received.frames)
        assertEquals(received.frames, sent.frameCount)
        assertNull("没开投递确认时结果里不该有确认对象", sent.delivery)
        assertFalse("没开回执时监听端不该写任何回执字节", received.receiptWritten)
        assertNull("没给挑战时两端都不该看到挑战帧", received.challenge)
    }

    /** 格 2：只开挑战。挑战逐字回显，监听端给了期望值时必须相等。 */
    @Test
    fun cell02_challengeAloneIsEchoedVerbatim() {
        val outcome = exchange(challenge = challenge)
        val sent = assertSentOk(outcome.client)
        val received = assertReceived(outcome.listener)
        assertEquals(challenge, received.challenge)
        assertEquals(challenge, sent.challenge)
    }

    // ------------------------------------------------------------------ 认证维

    /** 格 3：只开认证，两端同口令。tag 帧验过之后信封照收。 */
    @Test
    fun cell03_authAloneWithTheSameSecretSucceeds() {
        val outcome = exchange(
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
        )
        assertSentOk(outcome.client)
        assertArrayEquals(goldenEnvelope(), assertReceived(outcome.listener).envelope)
    }

    /**
     * 格 4：客户端口令错。监听端必须 auth-mismatch；客户端因为完全不读回话，只能报成功。
     *
     * 这条不对称是协议形状决定的（单向帧 + 可选 0x04），不是缺陷：发送端想知道「对方验过了」，
     * 必须显式开回执或投递确认——这正是下面几格存在的理由。
     */
    @Test
    fun cell04_aWrongSecretIsMismatchOnTheListenerAndInvisibleToTheSender() {
        val outcome = exchange(
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(wrongSecret),
        )
        assertEquals(SourceSyncTransferAuthReasons.AUTH_MISMATCH, listenerCodeOf(outcome.listener))
        assertSentOk(outcome.client)
    }

    /**
     * 格 5：监听端要认证、客户端根本没带 tag 帧。监听端 auth-missing。
     *
     * 注意 auth-required 不是这一格的码：它属于「本端请求了认证却没有可绑定 tag 的挑战」，
     * 也就是发送端在没宣布挑战的情况下开认证时在本地就失败、一个字节都不写（见格 14 的反面）。
     */
    @Test
    fun cell05_aListenerThatRequiresAuthRefusesAPeerWithoutATagFrame() {
        val outcome = exchange(
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
        )
        assertEquals(SourceSyncTransferAuthReasons.AUTH_MISSING, listenerCodeOf(outcome.listener))
        assertSentOk(outcome.client)
    }

    /** 格 6：监听端开了认证却没口令。这是配置错误，必须在读帧时报 auth-secret-unavailable。 */
    @Test
    fun cell06_aListenerWithAuthButNoSecretSaysSecretUnavailable() {
        val outcome = exchange(
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(null),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
        )
        assertEquals(
            SourceSyncTransferAuthReasons.AUTH_SECRET_UNAVAILABLE,
            listenerCodeOf(outcome.listener),
        )
    }

    // ------------------------------------------------------------------ 回执维

    /** 格 7：只开回执（接收端写、发送端等）。发送端拿到成功，监听端确认自己真写了 0x04。 */
    @Test
    fun cell07_receiptAloneIsWrittenAndAwaited() {
        val outcome = exchange(
            listenerReceipt = SourceSyncReceiptOptions.writing(),
            clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs),
        )
        assertSentOk(outcome.client)
        assertTrue("监听端开了回执就该写出 0x04", assertReceived(outcome.listener).receiptWritten)
    }

    /** 格 8：发送端等回执、接收端沉默。发送端必须 receipt-missing，监听端不该谎称写过回执。 */
    @Test
    fun cell08_awaitingAReceiptFromASilentListenerIsReceiptMissing() {
        val outcome = exchange(clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs))
        assertEquals(SourceSyncReceiptReasons.RECEIPT_MISSING, failedReasonOf(outcome.client))
        assertFalse(assertReceived(outcome.listener).receiptWritten)
    }

    // ------------------------------------------------------------------ 投递维

    /** 格 9：只开投递确认。监听端读完就关连接，发送端拿到 Confirmed。 */
    @Test
    fun cell09_deliveryAloneIsConfirmedByTheListenerClosing() {
        val outcome = exchange(delivery = SourceSyncDeliveryOptions.confirming(deliveryBudgetMs))
        val delivery = assertSentOk(outcome.client).delivery
        assertTrue("开了投递确认就该有确认对象，实际 " + delivery, delivery is SourceSyncDeliveryResult.Confirmed)
        assertTrue(
            "对端只是关连接，不该回任何字节",
            (delivery as SourceSyncDeliveryResult.Confirmed).bytesRead == 0,
        )
        assertReceived(outcome.listener)
    }

    /**
     * 格 10：对端既不关也不说话。投递确认必须折成 delivery-unconfirmed，且不抛异常。
     *
     * 这一格刻意不用监听端：SourceSyncTransferListener 收完一条连接就关，而本格要的正是「不关」。
     * 所以用一枚裸 ServerSocket 当对端：读走帧流后挂住不动，把预算耗光。
     */
    @Test
    fun cell10_aPeerThatNeverClosesLeavesDeliveryUnconfirmed() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val peer = silentPeer(server)
            val sent = SendCall(
                port = server.localPort,
                envelope = goldenEnvelope(),
                delivery = SourceSyncDeliveryOptions.confirming(600),
            ).start().await()
            peer.join(20_000)
            assertEquals(SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED, failedReasonOf(sent))
        }
    }

    /** 格 11：回执与投递同时开。回执优先：投递确认被忽略，结果里 delivery 必须是 null。 */
    @Test
    fun cell11_theReceiptWinsWhenBothAreEnabled() {
        val outcome = exchange(
            listenerReceipt = SourceSyncReceiptOptions.writing(),
            clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs),
            delivery = SourceSyncDeliveryOptions.confirming(deliveryBudgetMs),
        )
        val sent = assertSentOk(outcome.client)
        assertNull("回执开了以后投递确认必须被忽略", sent.delivery)
        assertTrue(assertReceived(outcome.listener).receiptWritten)
    }

    // ------------------------------------------------------------------ TLS 维

    /** 格 12：只开 TLS（指纹钉扎）。握手成功后信封照收。 */
    @Test
    fun cell12_tlsAloneCarriesTheEnvelope() {
        val outcome = exchange(tls = true)
        assertSentOk(outcome.client)
        assertArrayEquals(goldenEnvelope(), assertReceived(outcome.listener).envelope)
    }

    /**
     * 格 13：指纹钉错。客户端必须 tls-fingerprint-mismatch，且一个信封字节都没写出去。
     *
     * 监听端落哪个码取决于「客户端关连接时它正卡在哪一步」，所以这里只断言它没收到信封、
     * 且码落在握手失败 / 读帧失败 / 帧截断 / 超时四种之一——钉死具体哪一个就是自造 flaky。
     */
    @Test
    fun cell13_aWrongPinStopsBeforeAnyEnvelopeByte() {
        val outcome = exchange(tls = true, clientPin = pinOf(otherFixture))
        assertEquals(SourceSyncTlsReasons.FINGERPRINT_MISMATCH, failedReasonOf(outcome.client))
        assertTrue(
            "监听端不该收到信封，实际 " + describeListener(outcome.listener),
            outcome.listener is SourceSyncListenerTransfer.Failed,
        )
        assertTrue(
            "监听端的失败码应落在握手/读帧/截断/超时之内，实际 " + listenerCodeOf(outcome.listener),
            listenerCodeOf(outcome.listener) in setOf(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                SourceSyncTransferReasons.IO_FAILED,
                SourceSyncTransferReasons.FRAME_TRUNCATED,
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
            ),
        )
    }

    /** 格 14：TLS + 认证（同口令）。先握手再验 tag，两层都过。 */
    @Test
    fun cell14_tlsPlusAuthSucceeds() {
        val outcome = exchange(
            tls = true,
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
        )
        assertSentOk(outcome.client)
        assertArrayEquals(goldenEnvelope(), assertReceived(outcome.listener).envelope)
    }

    /** 格 15：TLS + 错口令。握手照谈成，认证在帧层被拒，监听端 auth-mismatch。 */
    @Test
    fun cell15_tlsPlusAWrongSecretFailsAtTheTagFrame() {
        val outcome = exchange(
            tls = true,
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(wrongSecret),
        )
        assertEquals(SourceSyncTransferAuthReasons.AUTH_MISMATCH, listenerCodeOf(outcome.listener))
    }

    /** 格 16：TLS + 回执。0x04 一样走加密流，不额外写任何东西。 */
    @Test
    fun cell16_tlsPlusReceiptConfirmsOverTheEncryptedStream() {
        val outcome = exchange(
            tls = true,
            listenerReceipt = SourceSyncReceiptOptions.writing(),
            clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs),
        )
        assertSentOk(outcome.client)
        assertTrue(assertReceived(outcome.listener).receiptWritten)
    }

    /** 格 17：TLS + 投递确认。监听端的关闭走 graceful（先关写端再收尾），发送端才读得到 FIN。 */
    @Test
    fun cell17_tlsPlusDeliveryIsConfirmedByAGracefulClose() {
        val outcome = exchange(
            tls = true,
            delivery = SourceSyncDeliveryOptions.confirming(deliveryBudgetMs),
        )
        val delivery = assertSentOk(outcome.client).delivery
        assertTrue("TLS 下的投递确认应成功，实际 " + delivery, delivery is SourceSyncDeliveryResult.Confirmed)
        assertReceived(outcome.listener)
    }

    /** 格 18：TLS + 认证 + 回执。tls- / auth- / receipt- 三张码表在同一条连接上都成立。 */
    @Test
    fun cell18_tlsPlusAuthPlusReceiptSucceeds() {
        val outcome = exchange(
            tls = true,
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
            listenerReceipt = SourceSyncReceiptOptions.writing(),
            clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs),
        )
        assertSentOk(outcome.client)
        val received = assertReceived(outcome.listener)
        assertEquals(challenge, received.challenge)
        assertTrue(received.receiptWritten)
    }

    /** 格 19：TLS + 认证 + 投递（不开回执）。 */
    @Test
    fun cell19_tlsPlusAuthPlusDeliverySucceeds() {
        val outcome = exchange(
            tls = true,
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
            delivery = SourceSyncDeliveryOptions.confirming(deliveryBudgetMs),
        )
        val delivery = assertSentOk(outcome.client).delivery
        assertTrue("投递确认应成功，实际 " + delivery, delivery is SourceSyncDeliveryResult.Confirmed)
        assertReceived(outcome.listener)
    }

    /**
     * 格 20：四维全开。矩阵里唯一「四个开关同时 true」的格子，断言也最完整：
     * 挑战回显、tag 帧验过、0x04 真写出、投递确认被回执压掉、客户端拿到成功。
     */
    @Test
    fun cell20_allFourDimensionsTogetherStillSucceed() {
        val outcome = exchange(
            tls = true,
            challenge = challenge,
            listenerAuth = SourceSyncTransferOptions.withSecret(secret),
            clientAuth = SourceSyncTransferOptions.withSecret(secret),
            listenerReceipt = SourceSyncReceiptOptions.writing(),
            clientReceipt = SourceSyncReceiptOptions.awaiting(receiptBudgetMs),
            delivery = SourceSyncDeliveryOptions.confirming(deliveryBudgetMs),
        )
        val sent = assertSentOk(outcome.client)
        val received = assertReceived(outcome.listener)
        assertEquals(challenge, received.challenge)
        assertArrayEquals(goldenEnvelope(), received.envelope)
        assertTrue("四维全开时回执应真写出去", received.receiptWritten)
        assertNull("回执优先：投递确认必须是 null", sent.delivery)
    }

    // ------------------------------------------------------------------ 矩阵驱动

    /** 一格的结果：客户端那一面与监听端那一面。 */
    private data class Outcome(
        val client: SourceSyncTransferResult?,
        val listener: SourceSyncListenerTransfer,
    )

    /**
     * 跑一格：起监听 → 后台线程 receiveOnce → 主线程真客户端拨号 sendTo → 收两边结果。
     *
     * @param tls 两端是否都开 TLS 1.3 并钉同一枚指纹（夹具固定，不随机）。
     * @param clientPin 覆盖客户端的钉扎指纹（只给「指纹错」那一格用）。
     * @param challenge 客户端宣布的挑战；监听端按同一串当期望值。
     */
    private fun exchange(
        tls: Boolean = false,
        challenge: String? = null,
        listenerAuth: SourceSyncTransferOptions? = null,
        clientAuth: SourceSyncTransferOptions? = null,
        listenerReceipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        clientReceipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        delivery: SourceSyncDeliveryOptions = SourceSyncDeliveryOptions.Off,
        clientPin: String = pinOf(receiverFixture),
    ): Outcome {
        val started = SourceSyncTransferListener.open(
            tls = if (tls) SourceSyncTlsOptions.pinned(pinOf(receiverFixture)) else SourceSyncTlsOptions.Off,
            identity = if (tls) identityOf(receiverFixture) else SourceSyncTlsIdentity.NONE,
            acceptTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
        )
        assertTrue("监听没能起来：" + describeStart(started), started is SourceSyncListenerStart.Started)
        val listener = (started as SourceSyncListenerStart.Started).listener
        try {
            val call = ReceiveCall(listener, challenge, listenerAuth, listenerReceipt).start()
            val sent = SendCall(
                port = listener.port,
                envelope = goldenEnvelope(),
                challenge = challenge,
                tls = if (tls) SourceSyncTlsOptions.pinned(clientPin) else SourceSyncTlsOptions.Off,
                auth = clientAuth,
                receipt = clientReceipt,
                delivery = delivery,
            ).start().await()
            return Outcome(sent, call.await())
        } finally {
            listener.close()
        }
    }

    /**
     * 格子 10 用的假对端：accept 之后把帧流读干净，然后既不关连接也不回话（让它挂到客户端自己超时）。
     * 客户端关连接后本线程读到 EOF 自然结束。
     */
    private fun silentPeer(server: ServerSocket): Thread {
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val scratch = ByteArray(512)
                    while (socket.getInputStream().read(scratch) >= 0) {
                        // 读掉即可：本格只关心「不关连接」这件事。
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "w6-57-silent-peer"
        }
        thread.start()
        return thread
    }

    /** 在后台线程里跑一次监听端接收；主线程当发送端。 */
    private class ReceiveCall(
        private val listener: SourceSyncTransferListener,
        private val expectedChallenge: String?,
        private val auth: SourceSyncTransferOptions?,
        private val receipt: SourceSyncReceiptOptions,
    ) {
        private val result = AtomicReference<SourceSyncListenerTransfer?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try {
                result.set(listener.receiveOnce(expectedChallenge, auth, receipt));
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-57-listener"
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

    /** 在后台线程里用真客户端拨号发送；主线程当监听端。 */
    private class SendCall(
        private val port: Int,
        private val envelope: ByteArray,
        private val challenge: String? = null,
        private val tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        private val auth: SourceSyncTransferOptions? = null,
        private val receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        private val delivery: SourceSyncDeliveryOptions = SourceSyncDeliveryOptions.Off,
    ) {
        private val result = AtomicReference<SourceSyncTransferResult?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val client = SourceSyncTransferClient(connectTimeoutMs = 5_000, readTimeoutMs = 10_000)
        private val thread = Thread {
            try {
                result.set(
                    client.sendTo(
                        host = "127.0.0.1",
                        port = port,
                        envelope = envelope,
                        challenge = challenge,
                        tls = tls,
                        auth = auth,
                        receipt = receipt,
                        delivery = delivery,
                    ),
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-57-send"
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
    }

    // ------------------------------------------------------------------ 夹具与断言

    private fun goldenEnvelope(): ByteArray =
        resourceStream("/sync/windows-envelope-device-a.json").use { it.readBytes() }

    private fun resourceStream(path: String): InputStream {
        val stream = javaClass.getResourceAsStream(path)
        assertTrue("夹具不存在：" + path, stream != null)
        return stream!!
    }

    private fun resourceText(path: String): String =
        resourceStream(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun tlsVectors(): JsonObject =
        Json.parseToJsonElement(resourceText("/tls/tls-vectors.json")).jsonObject

    private fun fixture(name: String): JsonObject {
        val match = tlsVectors().getValue("fixtures").jsonArray
            .map { it.jsonObject }
            .firstOrNull { it.getValue("name").jsonPrimitive.content == name }
        assertTrue("tls-vectors.json 里没有夹具 " + name, match != null)
        return match!!
    }

    private fun pinOf(name: String): String =
        fixture(name).getValue("spkiSha256Hex").jsonPrimitive.content

    private fun identityOf(name: String): SourceSyncTlsIdentity {
        val store = KeyStore.getInstance("PKCS12")
        resourceStream("/tls/" + fixture(name).getValue("file").jsonPrimitive.content).use { stream ->
            store.load(stream, password.toCharArray())
        }
        val identity = SourceSyncTlsIdentity.fromKeyStore(store, password.toCharArray())
        assertTrue("p12 夹具应该能变成可用的 TLS 身份", identity.isUsable)
        return identity
    }

    private fun assertSentOk(result: SourceSyncTransferResult?): SourceSyncTransferResult.Ok {
        assertTrue("客户端发送失败：" + describeSent(result), result is SourceSyncTransferResult.Ok)
        return result as SourceSyncTransferResult.Ok
    }

    private fun assertReceived(
        result: SourceSyncListenerTransfer,
    ): SourceSyncListenerTransfer.Received {
        assertTrue("监听端该收到信封，实际 " + describeListener(result), result is SourceSyncListenerTransfer.Received)
        return result as SourceSyncListenerTransfer.Received
    }

    private fun failedReasonOf(result: SourceSyncTransferResult?): String {
        assertTrue("客户端该失败，实际 " + describeSent(result), result is SourceSyncTransferResult.Failed)
        return (result as SourceSyncTransferResult.Failed).reason
    }

    private fun listenerCodeOf(result: SourceSyncListenerTransfer): String {
        assertTrue("监听端该失败，实际 " + describeListener(result), result is SourceSyncListenerTransfer.Failed)
        return (result as SourceSyncListenerTransfer.Failed).code
    }

    private fun describeSent(result: SourceSyncTransferResult?): String = when (result) {
        null -> "没有结果"
        is SourceSyncTransferResult.Ok -> "ok frames=" + result.frameCount + " delivery=" + result.delivery
        is SourceSyncTransferResult.Failed -> result.reason + " / " + (result.message ?: "")
    }

    private fun describeListener(result: SourceSyncListenerTransfer): String = when (result) {
        is SourceSyncListenerTransfer.Received ->
            "ok frames=" + result.frames + " bytes=" + result.bytes + " receipt=" + result.receiptWritten
        is SourceSyncListenerTransfer.Failed -> result.code + " / " + (result.detail ?: "")
    }

    private fun describeStart(started: SourceSyncListenerStart): String = when (started) {
        is SourceSyncListenerStart.Started -> "ok port=" + started.listener.port
        is SourceSyncListenerStart.Failed -> started.code + " / " + (started.detail ?: "")
    }
}
