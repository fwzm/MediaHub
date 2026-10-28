package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-A：429 冷却治理（先红后绿）。
 *
 * 规则（§04.3）：
 * - 429 + Retry-After 只作用于**同一作用域**的后续请求；窗口内不得再次出网；
 * - 不自动重放原请求（非幂等放大）；
 * - 冷却失败须是**可识别**的错误，且不得在拦截器里 sleep 阻塞线程。
 */
class RateLimitCooldownTest {

    private lateinit var server: MockWebServer
    private lateinit var factory: HttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        factory = HttpClientFactory(StdoutLogger())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a 429 with retry-after seconds suppresses the next request in the same scope`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        val client = factory.apiClient()
        val url = server.url("/Items")

        val first = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(429, first.code)
        assertEquals(1, server.requestCount)

        // 冷却窗口内再次请求：不得再次出网
        val second = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
        }
        assertEquals("冷却窗口内不得再次出网", 1, server.requestCount)
        assertTrue(
            "窗口内请求必须以可识别的冷却失败结束（而不是静默成功出网）",
            second.isFailure,
        )
    }

    @Test
    fun `an expired cooldown lets the next request through`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = factory.apiClient()
        val url = server.url("/Items")

        client.newCall(Request.Builder().url(url).build()).execute().close()
        val second = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(200, second.code)
        assertEquals(2, server.requestCount)
        second.close()
    }

    @Test
    fun `a 429 does not silently replay the original request`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val client = factory.apiClient()
        client.newCall(Request.Builder().url(server.url("/PlaybackInfo")).build()).execute().close()

        // 只允许第一条 429 出网；不得自动重放
        assertEquals(1, server.requestCount)
    }
}
