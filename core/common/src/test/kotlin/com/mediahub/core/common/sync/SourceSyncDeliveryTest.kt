package com.mediahub.core.common.sync

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-46：发送端的投递确认（对齐 Windows W6-30 的 SourceSyncDeliveryConfirmation）。
 *
 * 两件事在这里被钉住：
 * 1. 语义——「对端关了连接」是单向帧协议里唯一能证明「它读到了末尾」的信号；
 *    读不到 FIN（超时 / 对端还在发 / 连接出错）一律 delivery-unconfirmed，不折成传输失败。
 * 2. 默认不变——[SourceSyncDeliveryOptions.Off] 时不多读一次、不多等一毫秒，
 *    线上字节与 W6-22 / W6-25 冻结向量仍逐字节等价（Off 路径由既有向量套件继续钉住）。
 *
 * 对端是真 socket（ServerSocket 绑 127.0.0.1:0）与真 TLS 1.3 握手（tls 夹具的 p12），不是 mock：
 * 这一层的缺陷恰恰只在真连接的生命周期里出现（半关、RST、静默）。
 */
class SourceSyncDeliveryTest {

    private val tlsVectors: JsonObject by lazy {
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/tls/tls-vectors.json")) { "缺少 /tls/tls-vectors.json" }
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() },
        ).jsonObject
    }

    // ---------------------------------------------------------------- 码表与选项

    @Test
    fun reasonsAreTheFrozenSingleCode() {
        assertEquals(listOf("delivery-unconfirmed"), SourceSyncDeliveryReasons.ALL)
        assertEquals("delivery-unconfirmed", SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED)
    }

    @Test
    fun deliveryCodeDoesNotOverlapTheOtherFourTables() {
        val others = SourceSyncTransferReasons.ALL +
            SourceSyncTransferAuthReasons.ALL +
            SourceSyncTlsReasons.ALL +
            SourceSyncReceiptReasons.ALL
        for (code in SourceSyncDeliveryReasons.ALL) {
            assertTrue("投递码必须以 delivery- 开头：" + code, code.startsWith("delivery-"))
            assertFalse("投递码不得与其它码表重叠：" + code, others.contains(code))
        }
        assertEquals(
            SourceSyncTransferReasons.ALL.size + SourceSyncTransferAuthReasons.ALL.size +
                SourceSyncTlsReasons.ALL.size + SourceSyncReceiptReasons.ALL.size,
            others.size,
        )
        assertEquals(SourceSyncDeliveryReasons.ALL.size, SourceSyncDeliveryReasons.ALL.toSet().size)
    }

    @Test
    fun defaultOptionsAreOffAndSaySo() {
        assertFalse(SourceSyncDeliveryOptions.Off.confirm)
        assertEquals(0, SourceSyncDeliveryOptions.Off.budgetMs)
        assertEquals("SourceSyncDeliveryOptions { Confirm = false }", SourceSyncDeliveryOptions.Off.toString())
    }

    @Test
    fun confirmingSpeaksItsBudget() {
        val confirming = SourceSyncDeliveryOptions.confirming()
        assertTrue(confirming.confirm)
        assertEquals(2_000, confirming.budgetMs)
        assertEquals(
            "SourceSyncDeliveryOptions { Confirm = true, BudgetMs = 2000 }",
            confirming.toString(),
        )
        assertEquals(
            "SourceSyncDeliveryOptions { Confirm = true, BudgetMs = 250 }",
            SourceSyncDeliveryOptions.confirming(250).toString(),
        )
    }

    @Test
    fun budgetFallsBackToTheDefault() {
        assertEquals(2_000, SourceSyncDeliveryOptions.DEFAULT_BUDGET_MS)
        for (budget in listOf(null, 0, -5)) {
            assertEquals(2_000, SourceSyncDeliveryOptions.confirming(budget).budgetMs)
        }
    }

    @Test
    fun booleanHelperMapsToOffOrOn() {
        assertFalse(SourceSyncDeliveryOptions.confirmingOrOff(false).confirm)
        assertFalse(SourceSyncDeliveryOptions.confirmingOrOff(false, 500).confirm)
        assertTrue(SourceSyncDeliveryOptions.confirmingOrOff(true).confirm)
        assertEquals(500, SourceSyncDeliveryOptions.confirmingOrOff(true, 500).budgetMs)
        assertEquals(
            SourceSyncDeliveryOptions.DEFAULT_BUDGET_MS,
            SourceSyncDeliveryOptions.confirmingOrOff(true).budgetMs,
        )
    }

    @Test
    fun optionsNeverCarryASecret() {
        val texts = listOf(
            SourceSyncDeliveryOptions.Off.toString(),
            SourceSyncDeliveryOptions.confirming().toString(),
            SourceSyncDeliveryOptions.confirming(1234).toString(),
        )
        for (text in texts) {
            for (word in listOf("secret", "token", "password", "口令")) {
                assertFalse("选项文案不得带 " + word + "：" + text, text.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun maximumDrainIsSixtyFourKilobytes() {
        assertEquals(65_536, SourceSyncDeliveryConfirmation.MAXIMUM_DRAIN_BYTES)
        assertEquals(64 * 1024, SourceSyncDeliveryConfirmation.MAXIMUM_DRAIN_BYTES)
    }

    // ---------------------------------------------------------------- 纯流：setter 与失败折码

    @Test
    fun theReadTimeoutSetterSeesTheRemainingBudget() {
        val slices = mutableListOf<Int>()
        val result = SourceSyncDeliveryConfirmation.confirm(
            input = ByteArrayInputStream(ByteArray(0)),
            sentBytes = 665,
            budgetMs = 1_000,
            setReadTimeout = { millis -> slices.add(millis) },
        )
        assertTrue("读到流末尾就是确认", result is SourceSyncDeliveryResult.Confirmed)
        assertEquals(0, (result as SourceSyncDeliveryResult.Confirmed).bytesRead)
        assertEquals(1, slices.size)
        assertTrue("预算必须落在 1..1000，实际 " + slices[0], slices[0] in 1..1_000)
    }

    @Test
    fun aFailingReadTimeoutSetterIsReportedNotThrown() {
        val result = SourceSyncDeliveryConfirmation.confirm(
            input = ByteArrayInputStream(ByteArray(0)),
            sentBytes = 665,
            budgetMs = 500,
            setReadTimeout = { throw IllegalStateException("socket is closed") },
        )
        val unconfirmed = result as SourceSyncDeliveryResult.Unconfirmed
        assertEquals(SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED, unconfirmed.reason)
        assertTrue("detail 要说清是设置失败：" + unconfirmed.detail, unconfirmed.detail.contains("设不上"))
        assertTrue("detail 只带类名：" + unconfirmed.detail, unconfirmed.detail.contains("IllegalStateException"))
        assertFalse("detail 不得回显异常原文", unconfirmed.detail.contains("socket is closed"))
    }

    // ---------------------------------------------------------------- 真回环 socket

    @Test
    fun peerThatClosesAfterTheEnvelopeCountsAsConfirmed() {
        val envelope = envelopeOf("delivery-confirmed", "snap-1")
        val server = LoopbackPeer { socket -> socket.use { SourceSyncTransfer.receive(it.getInputStream()) } }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val result = client.sendTo(
                host = "127.0.0.1",
                port = server.port,
                envelope = envelope,
                delivery = SourceSyncDeliveryOptions.confirming(),
            )
            val ok = okOrFail(result, "对端读完就关，应该确认")
            val delivery = checkNotNull(ok.delivery) { "开了确认就必须带确认结果" }
            val confirmed = delivery as SourceSyncDeliveryResult.Confirmed
            assertEquals("对端没有额外发字节", 0, confirmed.bytesRead)
            assertTrue("耗时应为非负，实际 " + confirmed.elapsedMs, confirmed.elapsedMs >= 0)
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun bytesThePeerSentBeforeTheFinAreCounted() {
        val envelope = envelopeOf("delivery-bytes", "snap-2")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                it.getOutputStream().write(byteArrayOf(1, 2, 3, 4, 5))
                it.getOutputStream().flush()
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val ok = okOrFail(
                client.sendTo("127.0.0.1", server.port, envelope, delivery = SourceSyncDeliveryOptions.confirming()),
                "对端发几个字节再关，仍然算读到末尾",
            )
            val confirmed = ok.delivery as SourceSyncDeliveryResult.Confirmed
            assertEquals(5, confirmed.bytesRead)
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun aPeerThatKeepsTheConnectionOpenIsNotConfirmed() {
        val envelope = envelopeOf("delivery-open", "snap-3")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                Thread.sleep(900)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val result = client.sendTo(
                "127.0.0.1",
                server.port,
                envelope,
                delivery = SourceSyncDeliveryOptions.confirming(300),
            )
            val failed = failedOrFail(result, "对端不关连接就不能算送达")
            assertEquals(SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED, failed.reason)
            assertTrue(
                "detail 要说清等了多久：" + failed.message,
                checkNotNull(failed.message).contains("没有在 300 毫秒内关闭连接"),
            )
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun aTinyBudgetStillSaysWhichBudgetItUsed() {
        val envelope = envelopeOf("delivery-tiny", "snap-4")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                Thread.sleep(700)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val startedAt = System.nanoTime()
            val failed = failedOrFail(
                client.sendTo("127.0.0.1", server.port, envelope, delivery = SourceSyncDeliveryOptions.confirming(1)),
                "1 毫秒预算不该等到对端关闭",
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue("1 毫秒预算必须真的短，实际 " + elapsedMs + " ms", elapsedMs < 600)
            assertTrue(
                "预算原样写进 detail：" + failed.message,
                checkNotNull(failed.message).contains("没有在 1 毫秒内关闭连接"),
            )
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun aPeerThatKeepsTalkingIsNotConfirmed() {
        val envelope = envelopeOf("delivery-talk", "snap-5")
        val payload = ByteArray(70_000) { index -> (index % 251).toByte() }
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                it.getOutputStream().write(payload)
                it.getOutputStream().flush()
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 10_000)
            val failed = failedOrFail(
                client.sendTo(
                    "127.0.0.1",
                    server.port,
                    envelope,
                    delivery = SourceSyncDeliveryOptions.confirming(3_000),
                ),
                "对端在一封信封之后还在发，不是确认",
            )
            assertEquals(SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED, failed.reason)
            val detail = checkNotNull(failed.message)
            assertTrue("detail 要说清对端还在发：" + detail, detail.contains("还在发"))
            assertTrue("detail 要带已读字节数：" + detail, detail.contains("已读"))
            server.await(10_000)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun aResetConnectionIsNotConfirmed() {
        val envelope = envelopeOf("delivery-reset", "snap-6")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                it.getOutputStream().write(ByteArray(1_024))
                it.getOutputStream().flush()
                it.setSoLinger(true, 0)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val result = client.sendTo(
                "127.0.0.1",
                server.port,
                envelope,
                delivery = SourceSyncDeliveryOptions.confirming(2_000),
            )
            when (result) {
                is SourceSyncTransferResult.Failed -> {
                    assertEquals(SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED, result.reason)
                    assertTrue(
                        "RST 必须说清是连接问题，而不是超时：" + result.message,
                        checkNotNull(result.message).contains("连接出了问题"),
                    )
                }
                is SourceSyncTransferResult.Ok -> throw AssertionError(
                    "RST 之后不该出现 Confirmed（可能被当成正常 FIN）：" + result.delivery,
                )
            }
            server.await(10_000)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun detailNeverCarriesTheEnvelopeBytes() {
        val envelope = envelopeOf("marker-9f3a-never-echoed", "snap-7")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                Thread.sleep(800)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val failed = failedOrFail(
                client.sendTo("127.0.0.1", server.port, envelope, delivery = SourceSyncDeliveryOptions.confirming(200)),
                "对端不关连接",
            )
            val detail = checkNotNull(failed.message)
            assertFalse("detail 不得回显信封内容：" + detail, detail.contains("marker-9f3a"))
            assertTrue("detail 要带字节数：" + detail, detail.contains(envelope.size.toString()))
            server.await()
        } finally {
            server.closeQuietly()
        }
    }

    // ---------------------------------------------------------------- 默认关 / 回执优先

    @Test
    fun offDoesNotWaitForThePeerToClose() {
        val envelope = envelopeOf("delivery-off", "snap-8")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                Thread.sleep(2_500)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val startedAt = System.nanoTime()
            val ok = okOrFail(client.sendTo("127.0.0.1", server.port, envelope), "默认关闭确认时必须立刻返回")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertNull("关闭确认时没有确认结果", ok.delivery)
            assertTrue("默认关闭不该等对端关闭，实际 " + elapsedMs + " ms", elapsedMs < 1_500)
            server.await(10_000)
        } finally {
            server.closeQuietly()
        }
    }

    @Test
    fun deliveryIsIgnoredWhenTheReceiptIsAwaited() {
        val envelope = envelopeOf("delivery-receipt", "snap-9")
        val server = LoopbackPeer { socket ->
            socket.use {
                SourceSyncTransfer.receive(it.getInputStream())
                it.getOutputStream().write(byteArrayOf(0x04))
                it.getOutputStream().flush()
                Thread.sleep(800)
            }
        }
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 5_000)
            val ok = okOrFail(
                client.sendTo(
                    "127.0.0.1",
                    server.port,
                    envelope,
                    receipt = SourceSyncReceiptOptions.awaiting(2_000),
                    delivery = SourceSyncDeliveryOptions.confirming(150),
                ),
                "回执优先：对端回了 0x04 就算送达，不该再等它关闭",
            )
            assertNull("回执优先时投递确认被忽略", ok.delivery)
            server.await(10_000)
        } finally {
            server.closeQuietly()
        }
    }

    // ---------------------------------------------------------------- 真 TLS 1.3

    @Test
    fun aTlsPeerThatClosesAfterTheEnvelopeIsConfirmed() {
        val pin = fixturePin("mediahub-sync-test")
        val context = serverContext("mediahub-sync-test")
        val envelope = envelopeOf("delivery-tls", "snap-10")
        val listener = context.serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.enabledProtocols = arrayOf("TLSv1.3")
        listener.soTimeout = 20_000
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread {
            try {
                listener.accept().use { socket ->
                    SourceSyncTransfer.receive(socket.inputStream)
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                runCatching { listener.close() }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val client = SourceSyncTransferClient(readTimeoutMs = 10_000)
            val ok = okOrFail(
                client.sendTo(
                    host = "127.0.0.1",
                    port = listener.localPort,
                    envelope = envelope,
                    tls = SourceSyncTlsOptions.Pinned(pin),
                    delivery = SourceSyncDeliveryOptions.confirming(),
                ),
                "TLS 1.3 下对端关连接同样是投递确认",
            )
            assertTrue("TLS 也要有确认结果", ok.delivery is SourceSyncDeliveryResult.Confirmed)
            thread.join(10_000)
            if (thread.isAlive) throw AssertionError("TLS 对端线程没有结束")
            failure.get()?.let { throw AssertionError("TLS 对端失败：" + it.message, it) }
        } finally {
            runCatching { listener.close() }
        }
    }

    // ---------------------------------------------------------------- 夹具

    private fun envelopeOf(deviceId: String, snapshotId: String): ByteArray {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1," +
            "\"deviceId\":\"" + deviceId + "\",\"snapshotId\":\"" + snapshotId + "\"," +
            "\"capabilities\":{\"canSendSecrets\":false,\"sourceTypes\":[]}," +
            "\"sources\":[{\"syncSourceId\":\"local-" + snapshotId + "\",\"sourceType\":\"emby\"," +
            "\"displayName\":\"Emby " + snapshotId + "\",\"revision\":3," +
            "\"endpoints\":[{\"url\":\"http://127.0.0.1:8096\",\"priority\":1}]}]," +
            "\"deletions\":[]}"
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun fixturePin(name: String): String =
        tlsVectors.getValue("fixtures").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == name }
            .getValue("spkiSha256Hex")
            .jsonPrimitive
            .content

    private fun serverContext(name: String): SSLContext {
        val password = "mediahub-tls-test".toCharArray()
        val file = tlsVectors.getValue("fixtures").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == name }
            .getValue("file")
            .jsonPrimitive
            .content
        val bytes = checkNotNull(javaClass.getResourceAsStream("/tls/" + file)) { "缺少 /tls/" + file }
            .use { it.readBytes() }
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(bytes), password)
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, password)
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(factory.keyManagers, null, null)
        return context
    }

    private fun okOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Ok {
        if (result is SourceSyncTransferResult.Failed) {
            throw AssertionError(what + "，实际失败：" + result.reason + " / " + result.message)
        }
        return result as SourceSyncTransferResult.Ok
    }

    private fun failedOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Failed {
        if (result is SourceSyncTransferResult.Ok) {
            throw AssertionError(what + "，实际成功（确认=" + result.delivery + "）")
        }
        return result as SourceSyncTransferResult.Failed
    }

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
            if (thread.isAlive) throw AssertionError("回环对端线程 " + timeoutMs + " 毫秒内没有结束")
            failure.get()?.let { throw AssertionError("回环对端失败：" + it.message, it) }
        }

        fun closeQuietly() {
            runCatching { server.close() }
        }
    }
}
