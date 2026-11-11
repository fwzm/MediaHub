package com.mediahub.core.network

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
import java.util.concurrent.TimeUnit

/**
 * G5 · `record-only` 429 拦截器的**行为契约**（先红后绿）。
 *
 * ADR-046 的决策是：播放取流与图片加载**不接**冷却器的快速失败，只把 429 记进台账。
 * 本测试把这条决策钉死，防止将来有人「顺手统一」把冷却器铺到媒体链上 ——
 * 那会把可恢复的限流变成播放硬中断。
 *
 * 三条契约：
 * 1. 429 **原样返回**（不吞、不等待、不重试）；
 * 2. 台账 statusCounts[429] **递增**（说明确实被观测到）；
 * 3. **后续同源请求照样出网**（绝不抑制）。
 */
class RateLimitRecordOnlyInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(ledger: NetworkExchangeLedger?): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .apply { ledger?.let { addNetworkInterceptor(RateLimitRecordOnlyInterceptor(it)) } }
            .build()

    private fun get(client: OkHttpClient, url: String): Int {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        return response.use { it.code }
    }

    @Test
    fun `a 429 is returned verbatim and recorded, never retried`() {
        val ledger = NetworkExchangeLedger()
        val client = client(ledger)
        val url = server.url("/media/stream.mp4").toString()

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val started = System.currentTimeMillis()
        assertEquals("429 必须原样交回调用方", 429, get(client, url))
        val elapsed = System.currentTimeMillis() - started

        assertTrue(
            "record-only 拦截器不得因 Retry-After 退避；实际耗时 ${elapsed}ms",
            elapsed < 3_000,
        )
        assertEquals("只应发生一次请求（不重试）", 1, server.requestCount)

        val snap = ledger.snapshot()
        assertEquals(
            "429 必须被记进台账（这是接线的唯一目的）",
            1L,
            snap.statusCounts[429],
        )
    }

    @Test
    fun `after a 429 the same origin is NOT suppressed — requests still egress`() {
        // 这是与冷却拦截器的**关键区别**：record-only 绝不抑制后续请求。
        val ledger = NetworkExchangeLedger()
        val client = client(ledger)
        val url = server.url("/media/stream.mp4").toString()

        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(429, get(client, url))

        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-3/100").setBody("abcd"))
        assertEquals("429 之后同源请求仍必须真实出网（不抑制）", 206, get(client, url))

        assertEquals("服务器必须收到第二次请求", 2, server.requestCount)
        assertEquals("429 计数不应因后续成功而清零", 1L, ledger.snapshot().statusCounts[429])
    }

    @Test
    fun `without a ledger the client behaves exactly as before`() {
        // 未注入台账 = 完全不插拦截器，请求-响应语义与接入前逐字节一致。
        val client = client(null)
        val url = server.url("/media/stream.mp4").toString()
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(429, get(client, url))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non 429 responses are not recorded as 429`() {
        val ledger = NetworkExchangeLedger()
        val client = client(ledger)
        val url = server.url("/media/stream.mp4").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        assertEquals(200, get(client, url))
        assertTrue(
            "200 不得被误记为 429",
            ledger.snapshot().statusCounts[429] == null || ledger.snapshot().statusCounts[429] == 0L,
        )
    }
}
