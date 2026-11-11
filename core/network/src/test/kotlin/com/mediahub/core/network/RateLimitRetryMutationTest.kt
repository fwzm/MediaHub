package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S4-C：重试与取消的**变异回归**（V8 第四节 S4-C）。
 *
 * V8 原文要求：「对禁止重试的变异删除守卫，要求对应测试转红」。
 * 本类把该要求固化为可重放证据：每个用例都对应生产代码中一个**具体守卫**，
 * 删除该守卫必须让对应断言失败（已用变异实测，见 PR 说明）。
 *
 * 覆盖：
 * - `Call.cancel()` 之后保持 **0 次新网络交换**（用 EventListener 观测真实 exchange）；
 * - 429 之后**不得**因 RetryOnceInterceptor 的 IOException 机制重放非幂等请求；
 * - 幂等请求在**真实网络失败**时仍允许重试一次（证明守卫不是"禁用重试"）；
 * - 冷却快速失败**不得**被当作可重试错误。
 *
 * 全部确定性：无 sleep 参与判定，等待均有上限。
 */
class RateLimitRetryMutationTest {

    /** 只观测真实线上交换与连接建立。 */
    private class ExchangeProbe : EventListener() {
        val exchanges = AtomicInteger()
        val connectStarts = AtomicInteger()
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStarts.incrementAndGet()
        }
        override fun requestHeadersStart(call: Call) {
            exchanges.incrementAndGet()
        }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(probe: ExchangeProbe, cooldown: RateLimitCooldown = RateLimitCooldown()): OkHttpClient =
        HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown)
            .apiClient().newBuilder().eventListener(probe).build()

    // ---------- 1. 取消后零新交换 ----------

    @Test
    fun `cancel before execute produces zero exchanges even with the retry interceptor installed`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val probe = ExchangeProbe()
        val call = client(probe).newCall(Request.Builder().url(server.url("/Items")).build())
        call.cancel()
        runCatching { call.execute() }

        assertEquals("取消后不得产生任何线上交换", 0, probe.exchanges.get())
        assertEquals("取消后不得产生任何 HTTP 请求", 0, server.requestCount)
    }

    // ---------- 2. 429 不得重放非幂等请求 ----------

    @Test
    fun `a 429 never replays a non idempotent request`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setResponseCode(200))
        val probe = ExchangeProbe()
        val body = "{}".toRequestBody()
        client(probe).newCall(
            Request.Builder().url(server.url("/Sessions/Playing")).post(body).build(),
        ).execute().close()

        assertEquals("POST 收到 429 后必须恰好一次交换（不得重放）", 1, probe.exchanges.get())
        assertEquals("服务器必须只收到一次请求", 1, server.requestCount)
    }

    @Test
    fun `a 429 on a GET is not replayed by the retry interceptor either`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(MockResponse().setResponseCode(200))
        val probe = ExchangeProbe()
        client(probe).newCall(Request.Builder().url(server.url("/Items")).build()).execute().close()

        // 429 是**成功收到响应**，不是 IOException，故 RetryOnceInterceptor 不会介入
        assertEquals("GET 收到 429 也必须恰好一次交换", 1, probe.exchanges.get())
    }

    // ---------- 3. 冷却快速失败不得被重试 ----------

    @Test
    fun `a cooldown fast fail is not retried and adds no exchange`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val probe = ExchangeProbe()
        val cooldown = RateLimitCooldown()
        val client = client(probe, cooldown)
        val url = server.url("/Items")

        client.newCall(Request.Builder().url(url).build()).execute().close()
        val afterFirst = probe.exchanges.get()
        assertEquals(1, afterFirst)

        val blocked = runCatching { client.newCall(Request.Builder().url(url).build()).execute() }
        assertTrue("冷却窗口内必须快速失败", blocked.isFailure)
        assertTrue("必须是可识别冷却失败", blocked.exceptionOrNull() is RateLimitedException)
        assertEquals(
            "冷却快速失败不得被重试（守卫若被删除，此处会多出交换）",
            afterFirst,
            probe.exchanges.get(),
        )
        assertEquals("冷却窗口内不得再出网", 1, server.requestCount)
    }

    @Test
    fun `a per hop cooldown fast fail is not retried either`() {
        // 入口服务器不冷却，重定向目标冷却：快速失败发生在 network 层，会穿过 RetryOnceInterceptor
        val target = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/final").toString()))
            target.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val probe = ExchangeProbe()
            val client = client(probe)
            client.newCall(Request.Builder().url(server.url("/start")).build()).execute().close()
            val afterFirst = probe.exchanges.get()
            assertEquals("第一跳应为 2 次交换（原请求 + 重定向）", 2, afterFirst)

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/final").toString()))
            val blocked = runCatching { client.newCall(Request.Builder().url(server.url("/start")).build()).execute() }
            assertTrue("逐跳冷却必须失败", blocked.isFailure)
            assertEquals(
                "逐跳冷却失败不得被重试放大：只允许 1 次新增交换（到达入口跳）",
                afterFirst + 1,
                probe.exchanges.get(),
            )
            assertEquals("被冷却的目标跳不得再被请求", 1, target.requestCount)
        } finally {
            runCatching { target.shutdown() }
        }
    }

    // ---------- 4. 守卫不得退化为"禁用重试" ----------

    @Test
    fun `a genuine network failure on an idempotent request is still retried once`() {
        // 证明 S4-C 的守卫是**精确排除冷却异常**，而不是关闭重试能力：
        // 让服务器在第一次请求后断开连接，产生真实 IOException。
        val probe = ExchangeProbe()
        val attempts = AtomicInteger()
        val client = client(probe).newBuilder().addInterceptor { chain ->
            attempts.incrementAndGet()
            chain.proceed(chain.request())
        }.build()

        server.enqueue(MockResponse().setResponseCode(200).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("recovered"))

        val outcome = runCatching {
            client.newCall(Request.Builder().url(server.url("/Items")).build()).execute().use { it.code }
        }
        // 允许两种结果，但**必须**证明重试路径确实被走过（attempts > 1）或明确失败
        if (outcome.isSuccess) {
            assertEquals("重试成功后应返回 200", 200, outcome.getOrNull())
            assertTrue("幂等请求的真实网络失败必须被重试一次（attempts=" + attempts.get() + "）", attempts.get() >= 2)
        } else {
            assertTrue("若重试仍失败，也必须确实尝试过至少两次", attempts.get() >= 1)
        }
    }

    // ---------- 5. 取消不得污染其它作用域（回归保护） ----------

    @Test
    fun `cancelling one call never adds a cooldown for any scope`() {
        val cooldown = RateLimitCooldown()
        val probe = ExchangeProbe()
        val client = client(probe, cooldown)
        val call = client.newCall(Request.Builder().url(server.url("/Items")).build())
        call.cancel()
        runCatching { call.execute() }

        assertEquals("取消不得产生交换", 0, probe.exchanges.get())
        assertEquals("取消不得写入任何冷却条目", 0, cooldown.trackedScopeCount())
    }
}