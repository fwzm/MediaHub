package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S3 测试 4：重定向逐跳检查。
 *
 * 关键事实（OkHttp 链序）：**应用拦截器只看到原始请求与最终响应**；
 * 重定向由 RetryAndFollowUpInterceptor 在应用层之下逐跳跟随。
 * 因此"冷却归属"必须与**真正返回 429 的那一跳**一致，而不是把原始请求
 * （或最终 URL）的 host 当作整条链的身份。
 *
 * 契约：
 * - 429 归属 = serverIdFor(响应的 URL) 优先，未知时该 URL 的精确 origin；
 * - 第三方跳的 429 **不得**冷却原始服务器（跨服务器误冷却）；
 * - 被冷却的跳必须在**下一跳之前**快速失败，且不产生该跳的出网；
 * - 逐跳凭据剥离（ADR-030）继续生效：跨 origin 不得携带 Authorization / Cookie。
 *
 * 全部通过 MockWebServer 确定性构造，不使用 sleep、不关闭重定向。
 */
class RateLimitRedirectTest {

    /** 逐跳记录请求并按 URL 决定响应；trailing lambda 是唯一参数，避免重载歧义。 */
    private class CountingDispatcher(
        private val response: (RecordedRequest) -> MockResponse,
    ) : Dispatcher() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return response(request)
        }
    }

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var serverC: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        serverC = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        for (s in listOf(serverA, serverB, serverC)) runCatching { s.shutdown() }
    }

    private fun originOf(server: MockWebServer) = "http://${server.hostName}:${server.port}"

    private fun client(
        cooldown: RateLimitCooldown,
        serverIdFor: ServerIdResolver = ServerIdResolver { null },
        credentials: Boolean = false,
    ): OkHttpClient {
        val base = HttpClientFactory(
            logger = StdoutLogger(),
            serverIdFor = serverIdFor,
            rateLimitCooldown = cooldown,
        ).apiClient()
        return if (credentials) base.newBuilder().addNetworkInterceptor(OriginScopedCredentialInterceptor()).build()
        else base
    }

    private fun redirectTo(target: MockWebServer, path: String = "/downstream") =
        MockResponse().setResponseCode(302).setHeader("Location", target.url(path).toString())

    private fun get(client: OkHttpClient, server: MockWebServer, path: String = "/Items") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    // ---------- 1. 同 origin 重定向 ----------

    @Test
    fun `a same origin redirect chain keeps the cooldown on that origin`() {
        // A -> A(/step2) -> 429
        serverA.dispatcher = CountingDispatcher { req ->
            when (req.path) {
                "/step2" -> MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                else -> MockResponse().setResponseCode(302).setHeader("Location", serverA.url("/step2").toString())
            }
        }
        val cooldown = RateLimitCooldown()
        val client = client(cooldown)

        get(client, serverA).close()
        assertEquals("同 origin 重定向必须逐跳出网两次", 2, serverA.requestCount)
        assertNull("B/C 不得被牵连", cooldown.remainingMs(originOf(serverB)))
        assertNull("B/C 不得被牵连", cooldown.remainingMs(originOf(serverC)))

        val blocked = runCatching { get(client, serverA).close() }
        assertTrue("429 后同 origin 必须进入冷却", blocked.isFailure)
        assertTrue("必须是可识别冷却失败: $blocked", blocked.exceptionOrNull() is RateLimitedException)
        assertEquals("冷却窗口内不得再出网", 2, serverA.requestCount)
    }

    // ---------- 2. 不同 origin 重定向：归属必须跟随真实响应 ----------

    @Test
    fun `a 429 answered by a cross origin hop does not cool the origin server`() {
        // A 是"已知服务器 srv-a"，A -> B，B 返回 429
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB) }
        serverB.dispatcher = CountingDispatcher {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
        }
        val cooldown = RateLimitCooldown()
        val resolver = ServerIdResolver { url ->
            if (url.host == serverA.hostName && url.port == serverA.port) "srv-a" else null
        }
        val client = client(cooldown, resolver)

        assertEquals("链尾 429 必须原样返回", 429, get(client, serverA).code)
        assertEquals(1, serverA.requestCount)
        assertEquals(1, serverB.requestCount)

        // 真正返回 429 的是 B 的 origin —— 冷却必须记在 B 上
        assertNotNull(
            "429 必须记在真正返回它的那一跳（B 的精确 origin）上",
            cooldown.remainingMs(originOf(serverB)),
        )
        assertNull(
            "第三方跳的 429 不得冷却原始服务器 srv-a（跨服务器误冷却）",
            cooldown.remainingMs("srv-a"),
        )

        // 原始服务器必须仍然可用：后续请求应真实出网
        serverA.dispatcher = CountingDispatcher { MockResponse().setResponseCode(200) }
        val followUp = get(client, serverA)
        followUp.use { assertEquals("原始服务器不得被第三方 429 阻断", 200, it.code) }
        assertEquals("原始服务器必须真实出网", 2, serverA.requestCount)
    }

    @Test
    fun `a three hop chain records the cooldown on the hop that answered 429`() {
        // A -> B -> C，C 返回 429
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB, "/b") }
        serverB.dispatcher = CountingDispatcher { redirectTo(serverC, "/c") }
        serverC.dispatcher = CountingDispatcher {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "90")
        }
        val cooldown = RateLimitCooldown()
        val client = client(cooldown)

        assertEquals(429, get(client, serverA).code)
        assertEquals("每一跳必须恰好出网一次", 1, serverA.requestCount)
        assertEquals("每一跳必须恰好出网一次", 1, serverB.requestCount)
        assertEquals("每一跳必须恰好出网一次", 1, serverC.requestCount)

        assertNotNull("冷却必须归属 C", cooldown.remainingMs(originOf(serverC)))
        assertNull("A 不得被冷却", cooldown.remainingMs(originOf(serverA)))
        assertNull("B 不得被冷却", cooldown.remainingMs(originOf(serverB)))

        // 再走一次：C 已冷却，必须在到达 C 之前失败，且 A/B 各只多出网一次
        val blocked = runCatching { get(client, serverA).close() }
        assertTrue("链尾 429 后再次请求必须失败", blocked.isFailure)
        assertEquals("A 应被重访一次", 2, serverA.requestCount)
        assertEquals("B 应被重访一次", 2, serverB.requestCount)
        assertEquals("冷却的 C 不得再出网", 1, serverC.requestCount)
    }

    // ---------- 3. 不同端口重定向 ----------

    @Test
    fun `a redirect to another port on the same host keeps per origin isolation`() {
        // A -> B（同 host 不同 port），B 返回 429
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB) }
        serverB.dispatcher = CountingDispatcher {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
        }
        val cooldown = RateLimitCooldown()
        val client = client(cooldown)

        assertEquals(429, get(client, serverA).code)
        assertNotNull("冷却必须记在 B 的端口 origin 上", cooldown.remainingMs(originOf(serverB)))
        assertNull(
            "同 host 不同端口（A）不得被 B 的 429 误冷却",
            cooldown.remainingMs(originOf(serverA)),
        )

        // A 仍可用
        serverA.dispatcher = CountingDispatcher { MockResponse().setResponseCode(200) }
        get(client, serverA).use { assertEquals(200, it.code) }
        assertEquals(2, serverA.requestCount)
    }

    // ---------- 4. 凭据逐跳剥离 ----------

    @Test
    fun `credentials survive a same origin redirect and are stripped on a cross origin hop`() {
        // A -> A/next -> 200（同 origin，凭据保留）
        serverA.dispatcher = CountingDispatcher { req ->
            if (req.path == "/next") MockResponse().setResponseCode(200)
            else MockResponse().setResponseCode(302).setHeader("Location", serverA.url("/next").toString())
        }
        // B 是第三方跳：凭据必须被剥离
        serverB.dispatcher = CountingDispatcher { MockResponse().setResponseCode(200) }

        val cooldown = RateLimitCooldown()
        val client = client(cooldown, credentials = true)

        val sameOrigin = client.newCall(
            Request.Builder().url(serverA.url("/Items"))
                .header("Authorization", "Bearer secret-token")
                .header("Cookie", "session=secret")
                .build(),
        ).execute()
        sameOrigin.close()
        val sameOriginFinal = serverA.takeRequest() // 丢弃第一跳
        val redirected = serverA.takeRequest()
        assertEquals("/next", redirected.path)
        assertEquals(
            "同 origin 重定向必须保留凭据",
            "Bearer secret-token",
            redirected.getHeader("Authorization"),
        )
        assertEquals("session=secret", redirected.getHeader("Cookie"))

        // A -> B（跨 origin）：B 必须收不到任何凭据
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB, "/third-party") }
        val crossOrigin = client.newCall(
            Request.Builder().url(serverA.url("/Items"))
                .header("Authorization", "Bearer secret-token")
                .header("Cookie", "session=secret")
                .build(),
        ).execute()
        crossOrigin.use { assertEquals(200, it.code) }

        val thirdParty = serverB.takeRequest()
        assertEquals("/third-party", thirdParty.path)
        assertNull("跨 origin 必须剥离 Authorization", thirdParty.getHeader("Authorization"))
        assertNull("跨 origin 必须剥离 Cookie", thirdParty.getHeader("Cookie"))
        assertNotNull("UA 等安全头继续透传", thirdParty.getHeader("User-Agent"))
        assertEquals("重定向不得被关闭：B 必须真实收到请求", 1, serverB.requestCount)
    }

    // ---------- 5. 无关 origin 不受牵连 ----------

    @Test
    fun `an unrelated origin is never cooled by a redirect chain that ended in 429`() {
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB) }
        serverB.dispatcher = CountingDispatcher {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
        }
        serverC.enqueue(MockResponse().setResponseCode(200))

        val cooldown = RateLimitCooldown()
        val client = client(cooldown)

        assertEquals(429, get(client, serverA).code)
        assertNull("无关 origin C 不得被冷却", cooldown.remainingMs(originOf(serverC)))

        val unrelated = get(client, serverC)
        unrelated.use { assertEquals("无关服务器必须照常可用", 200, it.code) }
        assertEquals(1, serverC.requestCount)
    }

    // ---------- 6. 中间跳返回 429 ----------

    @Test
    fun `a 429 on an intermediate hop is attributed to that hop and stops the chain`() {
        // A -> B（B 返回 429，不得继续到 C）
        serverA.dispatcher = CountingDispatcher { redirectTo(serverB) }
        serverB.dispatcher = CountingDispatcher {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "45")
        }
        serverC.dispatcher = CountingDispatcher { MockResponse().setResponseCode(200) }

        val cooldown = RateLimitCooldown()
        val client = client(cooldown)

        assertEquals("中间跳 429 必须原样返回", 429, get(client, serverA).code)
        assertEquals("链在 B 终止", 0, serverC.requestCount)
        assertNotNull("冷却归属中间跳 B", cooldown.remainingMs(originOf(serverB)))
        assertNull("A 不得被误冷却", cooldown.remainingMs(originOf(serverA)))
    }
}
