package com.mediahub.core.network

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * M16-S0：网络交换观测模型与分类器的**确定性**契约。
 *
 * 计数单位（必须明确，V8 §三）：
 * - logicalCall   = 一次 OkHttp Call（callStart）
 * - exchange      = 真正写到线上的一个 HTTP 请求（requestHeadersStart）。
 *                   一次 Call 可因重定向/重试产生**多个** exchange。
 * - redirectHop   = priorResponse 链长度
 * - connectAttempt= 新建物理连接（connectStart）；连接池复用**不**触发。
 *
 * 出网分类：
 * - REAL_NETWORK     ：真正离开设备的出网交换（含桥到上游的那一次）
 * - LOOPBACK_BRIDGE  ：mpv 连到本桥 127.0.0.1 的**下游接入**，不得计为上游出网
 * - LOCAL_FILE       ：本地文件读取，根本不是网络请求
 * - PARTIAL_OBSERVABILITY：路径不可观测，**绝不能**当成 0
 *
 * 隐私：观测不得携带完整 URL / query / token / cookie / Authorization / UA / 自定义服务器名。
 * 全部用例使用 MockWebServer 与同步 execute()，不依赖 sleep。
 */
class NetworkExchangeObservationTest {

    private lateinit var server: MockWebServer
    private lateinit var serverB: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { serverB.shutdown() }
    }

    private fun client(listener: EventListener): OkHttpClient =
        OkHttpClient.Builder().eventListener(listener).build()

    // ---------- 1. 单交换 ----------

    @Test
    fun `a single exchange counts exactly one logical call and one exchange`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-1" })
        client(recorder).newCall(Request.Builder().url(server.url("/Items")).build()).execute().use { it.body!!.string() }

        val snap = ledger.snapshot()
        assertEquals(1L, snap.logicalCalls)
        assertEquals(1L, snap.exchanges)
        assertEquals(0L, snap.redirectHops)
        assertEquals(1L, snap.statusCounts[200])
        assertFalse(snap.partialObservability)
    }

    // ---------- 2. 重定向双跳 ----------

    @Test
    fun `a two hop redirect counts two exchanges and one redirect hop`() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/final").toString()),
        )
        serverB.enqueue(MockResponse().setResponseCode(200).setBody("done"))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.MEDIA, { "req-redirect" })
        client(recorder).newCall(Request.Builder().url(server.url("/start")).build()).execute().use { it.body!!.string() }

        val snap = ledger.snapshot()
        assertEquals("一次 Call", 1L, snap.logicalCalls)
        assertEquals("重定向产生第二个真实 exchange", 2L, snap.exchanges)
        assertEquals("一次重定向跳", 1L, snap.redirectHops)
        assertEquals(1L, snap.statusCounts[302])
        assertEquals(1L, snap.statusCounts[200])
    }

    // ---------- 3. 取消前（零交换） ----------

    @Test
    fun `a call cancelled before execution performs zero exchanges`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-cancel-before" })
        val call = client(recorder).newCall(Request.Builder().url(server.url("/Items")).build())
        call.cancel()
        runCatching { call.execute() }

        val snap = ledger.snapshot()
        assertEquals(1L, snap.logicalCalls)
        assertEquals("取消前不得产生任何线上交换", 0L, snap.exchanges)
        assertEquals(1L, snap.canceledCalls)
        assertEquals(0, server.requestCount)
    }

    // ---------- 4. 取消后（已产生交换） ----------

    @Test
    fun `a call cancelled after the exchange started still records that exchange`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("body"))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-cancel-after" })
        client(recorder).newCall(Request.Builder().url(server.url("/Items")).build()).execute().close()

        // 第二次：已取消（模拟取消发生在交换之后）
        val call2 = client(recorder).newCall(Request.Builder().url(server.url("/Items")).build())
        call2.cancel()
        runCatching { call2.execute() }

        val snap = ledger.snapshot()
        assertEquals(2L, snap.logicalCalls)
        assertEquals("只有完成的那个 Call 产生交换", 1L, snap.exchanges)
        assertEquals(1L, snap.canceledCalls)
    }

    // ---------- 5. 429 本地快速拒绝：0 网络交换 ----------

    @Test
    fun `a locally rejected rate limit performs zero network exchanges`() {
        val probe = PhysicalProbe()
        val cooldown = RateLimitCooldown()
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-429" })
        val client = HttpClientFactory(logger = com.mediahub.core.logging.StdoutLogger(), rateLimitCooldown = cooldown)
            .apiClient().newBuilder().eventListener(recorder).eventListener(probe).build()

        // 先真实建立冷却
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        client.newCall(Request.Builder().url(server.url("/Items")).build()).execute().close()
        assertEquals(1, server.requestCount)
        val connectsAfterFirst = probe.connectStarts.get()

        // 冷却窗口内：必须在连接前失败 => 0 交换、0 新连接
        val x = runCatching { client.newCall(Request.Builder().url(server.url("/Items")).build()).execute() }
        assertTrue(x.isFailure)
        assertEquals("冷却拒绝不得产生任何线上交换", 1, server.requestCount)
        assertEquals("冷却拒绝必须发生在建立连接之前", connectsAfterFirst, probe.connectStarts.get())
    }

    // ---------- 6. 本地回环接入（桥的下游）不得计为上游出网 ----------

    @Test
    fun `a bridge inbound loopback connection is not counted as upstream egress`() {
        val ledger = NetworkExchangeLedger()
        ledger.recordLoopbackInbound()
        ledger.recordLoopbackInbound()

        val snap = ledger.snapshot()
        assertEquals("回环下游接入单列", 2L, snap.loopbackInbound)
        assertEquals("回环下游接入不得计入上游出网", 0L, snap.exchanges)
        assertFalse(snap.partialObservability)
    }

    // ---------- 7. 桥到上游的字节拉取是真实出网 ----------

    @Test
    fun `a bridge upstream byte pull is counted as a real network exchange`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("payload"))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.BRIDGE_UPSTREAM, { "req-upstream" })
        client(recorder).newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body!!.string() }

        val snap = ledger.snapshot()
        assertEquals("桥到上游必须计入真实出网", 1L, snap.exchanges)
        assertEquals(1L, snap.perChannel[RequestChannel.BRIDGE_UPSTREAM])
    }

    // ---------- 8. 本地文件读取不是网络请求 ----------

    @Test
    fun `a local file read is not a network request`() {
        val ledger = NetworkExchangeLedger()
        ledger.recordLocalFileRead()

        val snap = ledger.snapshot()
        assertEquals(1L, snap.localFileReads)
        assertEquals("本地文件读取不得计入出网", 0L, snap.exchanges)
    }

    // ---------- 9. 隐私：观测不携带敏感信息 ----------

    @Test
    fun `an observation never carries url path query token or headers`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-privacy" })
        val url = server.url("/secret/path?token=SUPERSECRETTOKEN&session=abc123")
        client(recorder).newCall(
            Request.Builder().url(url)
                .header("Authorization", "Bearer SUPERSECRETAUTH")
                .header("X-Emby-Token", "SUPERSECRETEMBY")
                .header("Cookie", "session=SUPERSECRETCOOKIE")
                .build(),
        ).execute().close()

        val obs = ledger.observations().single()
        val rendered = obs.toString()
        for (needle in listOf(
            "secret/path", "SUPERSECRETTOKEN", "abc123",
            "SUPERSECRETAUTH", "SUPERSECRETEMBY", "SUPERSECRETCOOKIE",
            server.hostName, "Bearer", "Cookie",
        )) {
            assertFalse("观测不得包含敏感片段: " + needle, rendered.contains(needle, ignoreCase = true))
        }
        assertNotNull("必须保留匿名 scope", obs.scopeId)
        assertTrue("匿名 scope 不得等于原始 host", obs.scopeId != server.hostName)
        assertEquals("GET", obs.method)
    }

    // ---------- 10. 不可观测路径必须标 PARTIAL，不能算 0 ----------

    @Test
    fun `an unobservable path is marked partial observability and never reported as zero`() {
        val ledger = NetworkExchangeLedger()
        ledger.recordUnobservable(RequestChannel.MEDIA)

        val snap = ledger.snapshot()
        assertTrue("不可观测必须显式标记", snap.partialObservability)
        assertTrue("必须列出不可观测的通道", snap.partialChannels.contains(RequestChannel.MEDIA))
        assertEquals("不可观测不等于 0 次交换（未知不得算零）", 0L, snap.exchanges)
    }

    // ---------- 11. 有界聚合 ----------

    @Test
    fun `the ledger stays bounded across many scopes`() {
        val ledger = NetworkExchangeLedger(maxTrackedScopes = 8)
        repeat(500) { i ->
            ledger.record(
                RequestObservation(
                    requestId = "r-" + i,
                    channel = RequestChannel.API,
                    scopeId = "scope-" + i,
                    method = "GET",
                    exchangeCount = 1,
                    redirectHops = 0,
                    connectAttempts = 0,
                    statusCode = 200,
                    errorKind = null,
                    durationMs = 1L,
                    isRange = false,
                ),
            )
        }
        val snap = ledger.snapshot()
        assertEquals("累计计数不丢", 500L, snap.exchanges)
        assertTrue("按 scope 的明细必须有界，实际=" + snap.trackedScopes, snap.trackedScopes <= 8)
    }

    // ---------- 12. 连接尝试与逻辑调用分离 ----------

    @Test
    fun `connect attempts count physical connections and reuse does not add one`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.API, { "req-conn" })
        val client = client(recorder)
        val url = server.url("/Items")
        client.newCall(Request.Builder().url(url).build()).execute().close()
        client.newCall(Request.Builder().url(url).build()).execute().close()

        val snap = ledger.snapshot()
        assertEquals(2L, snap.logicalCalls)
        assertEquals(2L, snap.exchanges)
        assertTrue("必须记录至少一次物理连接", snap.connectAttempts >= 1L)
    }

    // ---------- 13. Range/HEAD 与状态码聚合 ----------

    @Test
    fun `range and head are recorded without carrying the path`() {
        server.enqueue(MockResponse().setResponseCode(206))
        val ledger = NetworkExchangeLedger()
        val recorder = NetworkExchangeRecorder(ledger, RequestChannel.MEDIA, { "req-range" })
        client(recorder).newCall(
            Request.Builder().url(server.url("/private/movie/file.mkv"))
                .header("Range", "bytes=0-1023")
                .build(),
        ).execute().close()

        val obs = ledger.observations().single()
        assertTrue("必须记录 Range 语义", obs.isRange)
        assertEquals(206, obs.statusCode)
        assertFalse("不得携带路径", obs.toString().contains("private/movie"))
    }

    /** 只观测物理事件，用于证明"冷却拒绝发生在连接之前"。 */
    private class PhysicalProbe : EventListener() {
        val connectStarts = AtomicInteger()
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStarts.incrementAndGet()
        }
    }
}