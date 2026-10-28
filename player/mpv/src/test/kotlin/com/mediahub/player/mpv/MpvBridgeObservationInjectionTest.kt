package com.mediahub.player.mpv

import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.NetworkExchangeLedger
import java.io.File
import java.net.Socket
import java.net.URI
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M16-S1 生产注入（V11 04-A/04-B）：确认 `createMpvBridge` 产出的桥在**生产构造链**上
 * 真正携带观测台账，而不是「API 存在但生产恒为 null」。
 *
 * 这是本轮发现的真实缺口：`createMpvBridge` 调用 `MpvHttpBridge(factory)`（只传 client 的
 * 次构造器），因此 ledger 永远为 null —— 计数恒为 0，无法区分「真实零交换」与「未接线」。
 */
class MpvBridgeObservationInjectionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Response(val status: Int, val body: ByteArray)

    private fun request(url: String): Response {
        val uri = URI(url)
        Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(("GET " + uri.path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val status = if (split > 0) text.substring(0, split).split("\r\n").first().split(' ')[1].toInt() else -1
            return Response(status, if (split > 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0))
        }
    }

    @Test
    fun `the production bridge factory produces an observable bridge`() {
        val ledger = NetworkExchangeLedger()
        val bridge = createMpvBridge(HttpClientFactory(com.mediahub.core.logging.StdoutLogger()), ledger)
        val file = temp.newFile("prod.mp4").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val url = bridge.startLocalFile(file.absolutePath)
        assertNotNull("桥必须返回可用 URL", url)
        try {
            assertEquals(200, request(url).status)
            val snap = ledger.snapshot()
            assertTrue(
                "生产构造链必须真正上报回环接入（当前为 " + snap.loopbackInbound + " 说明 ledger 未注入）",
                snap.loopbackInbound > 0L,
            )
            assertTrue("生产构造链必须真正上报本地文件读取", snap.localFileReads > 0L)
            assertEquals("本地播放不得产生上游出网", 0L, snap.exchanges)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `the production bridge factory stays observable for upstream HTTP`() {
        val ledger = NetworkExchangeLedger()
        val bridge = createMpvBridge(HttpClientFactory(com.mediahub.core.logging.StdoutLogger()), ledger)
        MockWebServer().use { upstream ->
            upstream.start()
            upstream.enqueue(okhttp3.mockwebserver.MockResponse().setBody("payload"))
            val url = bridge.start(upstream.url("/media").toString(), emptyMap())
            try {
                assertEquals(200, request(url).status)
                val snap = ledger.snapshot()
                assertTrue("回环接入必须被观测", snap.loopbackInbound > 0L)
                assertEquals("本地文件读取不得被误计到 HTTP 上游路径", 0L, snap.localFileReads)
            } finally {
                bridge.stop()
            }
        }
    }

    @Test
    fun `the bridge remains usable when created without a ledger`() {
        // 兼容性：旧调用形式必须继续可用（默认不观测，行为不变）
        val bridge = createMpvBridge(HttpClientFactory(com.mediahub.core.logging.StdoutLogger()))
        val file = temp.newFile("compat.mp4").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            assertEquals(200, request(url).status)
        } finally {
            bridge.stop()
        }
    }
}