package com.mediahub.core.network

import java.io.File
import java.net.Socket
import java.net.URI
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M16-S1：把观测模型接到**真实生产调用点**（桥的回环接入与本地字节源）。
 *
 * 背景（V10 03-A）：M16-S0 只交付了模型与分类器，`recordLoopbackInbound` /
 * `recordLocalFileRead` / `recordUnobservable` **没有任何生产调用者**，因此这三个计数在生产里
 * 恒为 0。恒为 0 与"真实零交换"在语义上完全不同 —— 本类先固定"事件确实被观测到"这一契约。
 *
 * 本类在接线前**必然失败**（RED），因为桥尚未上报任何观测事件。
 */
class BridgeObservationWiringTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun mediaFile(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private class Response(val status: Int, val body: ByteArray)

    private fun request(url: String, method: String = "GET", extra: String = ""): Response {
        val uri = URI(url)
        Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write((method + " " + uri.path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extra +
                    "Connection: close\r\n\r\n").toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val status = if (split > 0) text.substring(0, split).split("\r\n").first().split(' ')[1].toInt() else -1
            return Response(status, if (split > 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0))
        }
    }

    // ---------- 1. 核心 RED：本地文件读取必须被观测到 ----------

    @Test
    fun `a local file playback reports loopback inbound and local file reads`() {
        val file = mediaFile("observed.mp4", 2048)
        val ledger = NetworkExchangeLedger()
        val bridge = MpvHttpBridge(OkHttpClient(), ledger = ledger)
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val resp = request(url)
            assertEquals(200, resp.status)
            assertArrayEquals("字节内容必须正确（观测不得影响数据面）", file.readBytes(), resp.body)

            val snap = ledger.snapshot()
            assertTrue(
                "mpv 到本桥的回环接入必须被观测到（当前恒为 0 说明未接线），实际=" + snap.loopbackInbound,
                snap.loopbackInbound > 0L,
            )
            assertTrue(
                "本地文件读取必须被观测到（当前恒为 0 说明未接线），实际=" + snap.localFileReads,
                snap.localFileReads > 0L,
            )
            assertEquals("本地文件播放不得产生任何上游出网交换", 0L, snap.exchanges)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `loopback inbound is counted per accepted connection and never as upstream egress`() {
        val file = mediaFile("counted.mp4", 1024)
        val ledger = NetworkExchangeLedger()
        val bridge = MpvHttpBridge(OkHttpClient(), ledger = ledger)
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            repeat(3) { assertEquals(200, request(url).status) }
            val snap = ledger.snapshot()
            assertEquals("三次接入必须计为三次回环接入", 3L, snap.loopbackInbound)
            assertEquals("无论多少次回环接入都不得计为上游出网", 0L, snap.exchanges)
            assertTrue("本地文件读取必须被观测到", snap.localFileReads > 0L)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `the bridge works unchanged when no ledger is supplied`() {
        // 观测是**可选的**：不注入时行为必须与接线前完全一致（生产默认路径不变）
        val file = mediaFile("unobserved.mp4", 512)
        val bridge = MpvHttpBridge(OkHttpClient())
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val resp = request(url)
            assertEquals(200, resp.status)
            assertArrayEquals(file.readBytes(), resp.body)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 2. 不可观测路径必须显式登记，而不是显示为 0 ----------

    @Test
    fun `unobservable channels are declared rather than silently reported as zero`() {
        val ledger = NetworkExchangeLedger()
        // Media3 与 Coil 不在同一 OkHttp 链上（M15-S4-A 已确认），必须显式登记为不可观测
        ledger.recordUnobservable(RequestChannel.MEDIA)
        ledger.recordUnobservable(RequestChannel.IMAGE)

        val snap = ledger.snapshot()
        assertTrue("必须标记为部分可观测", snap.partialObservability)
        assertTrue(snap.partialChannels.contains(RequestChannel.MEDIA))
        assertTrue(snap.partialChannels.contains(RequestChannel.IMAGE))
        assertEquals("未知不得算零交换", 0L, snap.exchanges)
        assertTrue(
            "覆盖率说明必须显式体现不可观测，而不是报 0",
            snap.coverageNote.contains("PARTIAL_OBSERVABILITY"),
        )
    }

    // ---------- 3. 上游观测与回环观测不得双记 ----------

    @Test
    fun `bridge upstream exchange and loopback inbound are never double counted`() {
        val upstreamServer = okhttp3.mockwebserver.MockWebServer()
        upstreamServer.start()
        try {
            upstreamServer.enqueue(okhttp3.mockwebserver.MockResponse().setBody("payload"))
            val ledger = NetworkExchangeLedger()
            val client = OkHttpClient().newBuilder()
                .eventListener(NetworkExchangeRecorder(ledger, RequestChannel.BRIDGE_UPSTREAM, { "up" }))
                .build()
            val bridge = MpvHttpBridge(client, ledger = ledger)
            val url = bridge.start(upstreamServer.url("/media").toString(), emptyMap())
            try {
                assertEquals(200, request(url).status)
                val snap = ledger.snapshot()
                assertEquals("桥到上游是 1 次真实出网交换", 1L, snap.exchanges)
                assertEquals("回环接入单列，且不得与上游交换混计", 1L, snap.loopbackInbound)
            } finally {
                bridge.stop()
            }
        } finally {
            upstreamServer.shutdown()
        }
    }
}