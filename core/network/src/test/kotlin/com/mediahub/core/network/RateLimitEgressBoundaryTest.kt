package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S3 测试 6：冷却的**出网边界**与重试放大。
 *
 * 用 OkHttp [EventListener] 观测 TCP 连接建立（`connectStart`）与 HTTP 请求（`requestHeadersStart`），
 * 从而区分"没有发出 HTTP 请求"与"根本没有建立连接"——MockWebServer.requestCount 只能看到后者以外的
 * 前一种，无法证明连接层没有churn。
 *
 * 契约：
 * - 已在冷却中的作用域必须在**建立连接之前**失败（零 connectStart、零 HTTP 请求）；
 * - 冷却快速失败不得被 RetryOnceInterceptor 当作可重试网络错误放大（每个请求只有一次尝试）。
 */
class RateLimitEgressBoundaryTest {

    /** 只记录连接层与请求层事件，不参与任何等待。 */
    private class EgressProbe : EventListener() {
        val connectStarts = AtomicInteger()
        val requestHeadersStarts = AtomicInteger()
        val connectFailures = AtomicInteger()

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStarts.incrementAndGet()
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?,
            ioe: IOException,
        ) {
            connectFailures.incrementAndGet()
        }

        override fun requestHeadersStart(call: Call) {
            requestHeadersStarts.incrementAndGet()
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

    private fun probeClient(probe: EgressProbe, cooldown: RateLimitCooldown): OkHttpClient =
        HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown)
            .apiClient().newBuilder().eventListener(probe).build()

    @Test
    fun `a cooled scope fails before any connection is established`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val cooldown = RateLimitCooldown()
        val probe = EgressProbe()
        val client = probeClient(probe, cooldown)
        val url = server.url("/Items")

        // 第一次：真实 429，建立冷却（这一步允许有连接与请求）
        client.newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(1, server.requestCount)
        val connectAfterFirst = probe.connectStarts.get()
        assertTrue("首次请求必须建立连接", connectAfterFirst >= 1)

        // 第二次：处于冷却窗口 —— 必须"连接前"失败
        val thrown = runCatching { client.newCall(Request.Builder().url(url).build()).execute() }.exceptionOrNull()
        assertTrue(
            "冷却期间必须以可识别错误失败，实际=$thrown",
            thrown is RateLimitedException,
        )
        assertEquals(
            "冷却期间不得建立任何新连接（应用层判定必须位于 ConnectInterceptor 之上）",
            connectAfterFirst,
            probe.connectStarts.get(),
        )
        assertEquals("冷却期间不得再次出网", 1, server.requestCount)
    }

    /**
     * 重定向链上的**逐跳**冷却由 network 层拦截器判定，它位于 RetryOnceInterceptor 之下，
     * 因此冷却失败会穿过重试循环 —— 重试守卫（RetryOnceInterceptor 显式排除
     * [RateLimitedException]）是这条路径唯一的防放大措施，必须被测试固定。
     *
     * 入口作用域未被冷却（应用层放行），但重定向目标已被冷却：这正是守卫生效的场景。
     */
    @Test
    fun `a per hop cooldown fast fail is never retried by the api client`() {
        val serverA = MockWebServer().apply { start() }
        val serverB = MockWebServer().apply { start() }
        try {
            val cooldown = RateLimitCooldown()
            val attempts = AtomicInteger()

            val base = HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown).apiClient()
            // RetryOnceInterceptor 是最外层应用拦截器，其后追加的拦截器位于重试循环**内部**：
            // 每次重试都会再次经过它，因此 attempts 精确反映真实尝试次数。
            val client = base.newBuilder()
                .addInterceptor { chain ->
                    attempts.incrementAndGet()
                    chain.proceed(chain.request())
                }
                .build()

            // A（入口，永不 429）--302--> B（429）
            serverA.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setResponseCode(302)
                        .setHeader("Location", serverB.url("/downstream").toString())
            }
            serverB.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
            }

            // 第一次：A 放行 → 跟随到 B → B 返回 429，B 进入冷却
            client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute().close()
            assertEquals("第一跳只允许一次尝试", 1, attempts.get())
            assertEquals(1, serverA.requestCount)
            assertEquals(1, serverB.requestCount)
            val cooledTo = cooldown.remainingMs(originOf(serverB))
            assertTrue("B 必须已进入冷却，实际=$cooledTo", cooledTo != null && cooledTo > 0L)

            // 第二次：应用层检查入口 A（未冷却）放行 → network 层发现 B 已冷却并快速失败。
            // 守卫若被删除，RetryOnceInterceptor 会重试 → attempts 变成 2 以上。
            val thrown = runCatching {
                client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute()
            }.exceptionOrNull()
            assertTrue("逐跳冷却必须表现为可识别错误，实际=$thrown", thrown is RateLimitedException)
            assertEquals(
                "冷却失败不得被重试放大：只允许一次尝试（守卫被删除此处会大于 1）",
                2,
                attempts.get(),
            )
            assertEquals("冷却的目标跳不得再产生 HTTP 请求", 1, serverB.requestCount)
        } finally {
            runCatching { serverA.shutdown() }
            runCatching { serverB.shutdown() }
        }
    }

    private fun originOf(s: MockWebServer) = "http://" + s.hostName + ":" + s.port

    @Test
    fun `a media client also refuses egress for a cooled scope`() {
        // mediaClient 没有 RetryOnceInterceptor，但同样必须做连接前的冷却判定
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val cooldown = RateLimitCooldown()
        val probe = EgressProbe()
        val client = HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown)
            .mediaClient().newBuilder().eventListener(probe).build()
        val url = server.url("/stream/1")

        client.newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(1, server.requestCount)
        val connects = probe.connectStarts.get()

        val thrown = runCatching { client.newCall(Request.Builder().url(url).build()).execute() }.exceptionOrNull()
        assertTrue("媒体客户端在冷却期间必须快速失败，实际=$thrown", thrown is RateLimitedException)
        assertEquals("媒体客户端冷却期间不得建立新连接", connects, probe.connectStarts.get())
        assertEquals("媒体客户端冷却期间不得再出网", 1, server.requestCount)
    }
}