package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S4-B：冷却容量的**分区保护**（对抗性测试）。
 *
 * 已登记的缺陷（安全残余风险，M15-S3 遗留）：
 * 冷却表满员时按"最早到期"淘汰，因此**攻击者可控的大量未知 origin 可以挤掉核心媒体源的冷却**
 * —— 约 256 个 429 就能让主服务器重新可被请求（fail-open）。
 *
 * 修复契约：
 * - **已知服务器作用域**（serverIdFor 非 null）进入受保护分区，**绝不被未知 origin 驱逐**；
 * - **未知 origin** 进入独立分区，只在**自己分区内**互相淘汰；
 * - 两分区容量之和仍有**硬上限**（不得无限扩容，也不得全局阻断）；
 * - 过期条目仍按期回收，受保护分区不会因此永久占满。
 *
 * 所有压力均由自建 MockWebServer 与**受控时钟**制造，绝不对真实服务器施压。
 */
class RateLimitCapacityIsolationTest {

    private lateinit var coreServer: MockWebServer
    private lateinit var coreServerB: MockWebServer

    @Before
    fun setUp() {
        coreServer = MockWebServer().apply { start() }
        coreServerB = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { coreServer.shutdown() }
        runCatching { coreServerB.shutdown() }
    }

    private fun originOf(s: MockWebServer) = "http://" + s.hostName + ":" + s.port

    /** 把核心服务器登记为"已知服务器"，其余一律未知。 */
    private fun resolverForCore(): ServerIdResolver = ServerIdResolver { url ->
        if (url.host == coreServer.hostName && url.port == coreServer.port) "core-media-server" else null
    }

    // ---------- 1. 对抗性核心断言 ----------

    @Test
    fun `an unknown origin flood cannot evict a known server cooldown`() {
        var now = 0L
        val cooldown = RateLimitCooldown(clockMs = { now })

        // 核心媒体源（已知服务器）进入冷却
        cooldown.apply("core-media-server", 60_000L, isKnownServer = true)
        assertNotNull("核心源必须已冷却", cooldown.remainingMs("core-media-server"))

        // 攻击者注入远多于容量的未知 origin（各自不同的 429）
        for (i in 1..(RateLimitCooldown.MAX_TRACKED_SCOPES * 8)) {
            cooldown.apply("https://attacker-" + i + ".example.com:443", 60_000L, isKnownServer = false)
        }

        assertNotNull(
            "核心媒体源的冷却**不得**被未知 origin 洪水挤掉（当前为 fail-open 缺陷）",
            cooldown.remainingMs("core-media-server"),
        )
    }

    @Test
    fun `known server cooldowns survive even under sustained unknown pressure`() {
        var now = 0L
        val cooldown = RateLimitCooldown(clockMs = { now })
        val known = (1..8).map { "known-server-" + it }
        known.forEach { cooldown.apply(it, 60_000L, isKnownServer = true) }

        // 多轮洪水，并在轮次之间推进时钟（模拟长时间攻击）
        repeat(6) { round ->
            for (i in 1..RateLimitCooldown.MAX_TRACKED_SCOPES) {
                cooldown.apply("https://r" + round + "-" + i + ".example.com:443", 30_000L, isKnownServer = false)
            }
            now += 1_000L
        }

        known.forEach { scope ->
            assertNotNull("已知服务器 " + scope + " 的冷却不得被挤掉", cooldown.remainingMs(scope))
        }
    }

    // ---------- 2. 端到端：洪水后核心源仍被阻断出网 ----------

    @Test
    fun `after an unknown origin flood the core server still cannot egress`() {
        val probe = EgressProbe()
        val cooldown = RateLimitCooldown()
        val client = HttpClientFactory(
            logger = StdoutLogger(),
            serverIdFor = resolverForCore(),
            rateLimitCooldown = cooldown,
        ).apiClient().newBuilder().eventListener(probe).build()

        // 核心源真实返回 429，建立冷却
        coreServer.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        client.newCall(Request.Builder().url(coreServer.url("/Items")).build()).execute().close()
        assertEquals(1, coreServer.requestCount)
        val connects = probe.connectStarts.get()

        // 攻击者洪水：大量未知 origin 各自产生 429（全部走自建 MockWebServer 的冷却记账路径）
        for (i in 1..(RateLimitCooldown.MAX_TRACKED_SCOPES * 4)) {
            cooldown.apply("https://attacker-" + i + ".example.com:443", 60_000L, isKnownServer = false)
        }

        // 核心源必须仍然被阻断：零新出网、零新连接
        val blocked = runCatching { client.newCall(Request.Builder().url(coreServer.url("/Items")).build()).execute() }
        assertTrue("洪水后核心源仍必须以冷却错误快速失败", blocked.isFailure)
        assertTrue("必须是可识别冷却失败", blocked.exceptionOrNull() is RateLimitedException)
        assertEquals("洪水后核心源不得再出网", 1, coreServer.requestCount)
        assertEquals("洪水后核心源不得建立新连接", connects, probe.connectStarts.get())
    }

    @Test
    fun `other legitimate known servers keep working while one is cooled`() {
        val cooldown = RateLimitCooldown()
        val resolver = ServerIdResolver { url ->
            when {
                url.host == coreServer.hostName && url.port == coreServer.port -> "core-media-server"
                url.host == coreServerB.hostName && url.port == coreServerB.port -> "second-media-server"
                else -> null
            }
        }
        val client = HttpClientFactory(
            logger = StdoutLogger(),
            serverIdFor = resolver,
            rateLimitCooldown = cooldown,
        ).apiClient()

        coreServer.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        client.newCall(Request.Builder().url(coreServer.url("/Items")).build()).execute().close()

        // 未知 origin 洪水
        for (i in 1..(RateLimitCooldown.MAX_TRACKED_SCOPES * 4)) {
            cooldown.apply("https://noise-" + i + ".example.com:443", 60_000L, isKnownServer = false)
        }

        // 另一台合法服务器必须照常工作
        coreServerB.enqueue(MockResponse().setResponseCode(200))
        val ok = client.newCall(Request.Builder().url(coreServerB.url("/Items")).build()).execute()
        ok.use { assertEquals("其它合法服务器必须照常可用", 200, it.code) }
        assertEquals(1, coreServerB.requestCount)
    }

    // ---------- 3. 有界性与回收 ----------

    @Test
    fun `the total tracked scopes stay bounded under combined pressure`() {
        var now = 0L
        val cooldown = RateLimitCooldown(clockMs = { now })
        for (i in 1..RateLimitCooldown.MAX_TRACKED_SCOPES) {
            cooldown.apply("known-" + i, 60_000L, isKnownServer = true)
            cooldown.apply("https://unknown-" + i + ".example.com:443", 60_000L, isKnownServer = false)
        }
        assertTrue(
            "总量必须仍有硬上限：上限=" + RateLimitCooldown.MAX_TRACKED_SCOPES +
                 " 实际=" + cooldown.trackedScopeCount(),
            cooldown.trackedScopeCount() <= RateLimitCooldown.MAX_TRACKED_SCOPES,
        )
        // 受保护分区自身也必须有界，不能靠"无限扩容"来保护已知服务器
        assertTrue("已知分区必须有界", cooldown.knownScopeCount() <= RateLimitCooldown.MAX_KNOWN_SCOPES)
    }

    @Test
    fun `unknown origins are evicted among themselves without touching known scopes`() {
        var now = 0L
        val cooldown = RateLimitCooldown(clockMs = { now })
        // 核心源冷却足够长（10 分钟），远长于注入过程消耗的时钟推进量
        cooldown.apply("core-media-server", 600_000L, isKnownServer = true)

        // 注入远超未知分区容量的不同 origin。
        // 每次插入都推进时钟，使各条目的截止时间**互不相同** —— 否则"最早到期"
        // 存在并列，淘汰结果取决于 ConcurrentHashMap 迭代顺序（不可测）。
        for (i in 1..(RateLimitCooldown.MAX_UNKNOWN_SCOPES * 3)) {
            now += 1L
            cooldown.apply("https://u" + i + ".example.com:443", 600_000L, isKnownServer = false)
        }

        assertNotNull("核心源仍在冷却", cooldown.remainingMs("core-media-server"))
        // 最早插入（截止时间最小）的未知 origin 应已被淘汰（分区内互相淘汰）
        assertNull("最早的未知 origin 应被分区内淘汰", cooldown.remainingMs("https://u1.example.com:443"))
        // 最新的未知 origin 应仍在
        assertNotNull(
            "最新的未知 origin 应仍在",
            cooldown.remainingMs("https://u" + (RateLimitCooldown.MAX_UNKNOWN_SCOPES * 3) + ".example.com:443"),
        )
    }

    @Test
    fun `an expired known scope frees its protected slot for a new one`() {
        var now = 0L
        val cooldown = RateLimitCooldown(clockMs = { now }, maxKnownScopes = 2)
        cooldown.apply("known-a", 1_000L, isKnownServer = true)
        cooldown.apply("known-b", 60_000L, isKnownServer = true)
        assertEquals(2, cooldown.knownScopeCount())

        // a 过期后，新服务器必须能占位（受保护分区不得永久占满）
        now = 1_000L
        cooldown.apply("known-c", 60_000L, isKnownServer = true)
        assertNotNull("新已知服务器必须能进入受保护分区", cooldown.remainingMs("known-c"))
        assertNotNull("未过期的 known-b 必须保留", cooldown.remainingMs("known-b"))
        assertTrue("已知分区不得超限", cooldown.knownScopeCount() <= 2)
    }

    @Test
    fun `concurrent known and unknown writers keep both partitions bounded`() {
        val cooldown = RateLimitCooldown()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                repeat(400) { i ->
                    if (t % 2 == 0) cooldown.apply("known-" + t + "-" + i, 60_000L, isKnownServer = true)
                    else cooldown.apply("https://x" + t + "-" + i + ".example.com:443", 60_000L, isKnownServer = false)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发写入必须完成", done.await(30, TimeUnit.SECONDS))
        assertTrue("总量有界", cooldown.trackedScopeCount() <= RateLimitCooldown.MAX_TRACKED_SCOPES)
        assertTrue("已知分区有界", cooldown.knownScopeCount() <= RateLimitCooldown.MAX_KNOWN_SCOPES)
    }

    private class EgressProbe : EventListener() {
        val connectStarts = AtomicInteger()
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStarts.incrementAndGet()
        }
    }
}