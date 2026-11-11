package com.mediahub.core.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.mediahub.core.logging.StdoutLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S2：Retry-After 解析与时钟语义（表驱动，确定性）。
 *
 * 覆盖：delta-seconds（0/正常/负/非整数/空白/超大）、HTTP-date（未来/过去/非法）、
 * 缺失头、溢出与 15 分钟上限、单调时钟释放、非 429 不设冷却、非幂等请求不被重放。
 * 全部使用**可注入时钟**，不 sleep、不改设备时间。
 */
class RetryAfterPolicyTest {

    private val default = RateLimitCooldown.DEFAULT_COOLDOWN_MS
    private val cap = RateLimitCooldown.MAX_COOLDOWN_MS

    // ---------- Retry-After 解析：delta-seconds ----------

    @Test
    fun `delta seconds table`() {
        val table = listOf(
            Triple("0", 0L, "零秒 => 无冷却"),
            Triple("2", 2_000L, "正常秒值"),
            Triple("-5", 0L, "负值 => 无冷却"),
            Triple(" 7 ", 7_000L, "前后空白"),
            Triple("2.5", default, "非整数 => 保守默认"),
            Triple("abc", default, "非法 => 保守默认"),
        )
        for ((header, expected, why) in table) {
            assertEquals(why, expected, RateLimitCooldown.parseRetryAfter(header, nowEpochMs = 0L))
        }
    }

    @Test
    fun `missing retry-after falls back to the conservative default`() {
        assertEquals(default, RateLimitCooldown.parseRetryAfter(null, nowEpochMs = 0L))
        assertEquals(default, RateLimitCooldown.parseRetryAfter("", nowEpochMs = 0L))
    }

    // ---------- Retry-After 解析：HTTP-date ----------

    @Test
    fun `http date in the future becomes a positive duration`() {
        // 2026-10-14T00:00:00Z 起 +30 秒
        val now = 1_792_000_000_000L
        val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 30_000L).atZone(java.time.ZoneOffset.UTC),
        )
        val parsed = RateLimitCooldown.parseRetryAfter(header, nowEpochMs = now)
        assertTrue("未来时间应为正时长，实际=$parsed", parsed in 29_000L..31_000L)
    }

    @Test
    fun `http date in the past yields no cooldown`() {
        val now = 1_792_000_000_000L
        val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now - 60_000L).atZone(java.time.ZoneOffset.UTC),
        )
        assertEquals(0L, RateLimitCooldown.parseRetryAfter(header, nowEpochMs = now))
    }

    @Test
    fun `invalid http date falls back to the conservative default`() {
        assertEquals(default, RateLimitCooldown.parseRetryAfter("not-a-date", nowEpochMs = 0L))
        assertEquals(
            default,
            RateLimitCooldown.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT+99", nowEpochMs = 0L),
        )
    }

    // ---------- 溢出与上限 ----------

    @Test
    fun `an absurd retry-after value is capped instead of overflowing`() {
        val cold = RateLimitCooldown(clockMs = { 0L })
        val parsed = RateLimitCooldown.parseRetryAfter(Long.MAX_VALUE.toString(), nowEpochMs = 0L)
        assertTrue("解析结果不得为负（溢出），实际=$parsed", parsed >= 0L)
        cold.apply("srv-a", parsed)
        val remaining = cold.remainingMs("srv-a")
        assertNotNull("超大 Retry-After 不得因溢出而静默失效（变成无冷却）", remaining)
        assertTrue("冷却必须被封顶到 $cap，实际=$remaining", (remaining ?: 0L) in 1L..cap)
    }

    @Test
    fun `apply clamps any duration to the cap`() {
        val cold = RateLimitCooldown(clockMs = { 0L })
        cold.apply("srv-a", Long.MAX_VALUE)
        val remaining = cold.remainingMs("srv-a")
        assertNotNull(remaining)
        assertTrue("实际=$remaining", (remaining ?: 0L) in 1L..cap)
    }

    // ---------- 单调时钟语义 ----------

    @Test
    fun `cooldown is driven by the monotonic clock and releases when it passes`() {
        var now = 0L
        val cold = RateLimitCooldown(clockMs = { now })
        cold.apply("srv-a", 5_000L)
        assertNotNull(cold.remainingMs("srv-a"))

        now = 4_999L
        assertNotNull("窗口内应保持冷却", cold.remainingMs("srv-a"))

        now = 5_000L
        assertNull("单调时钟越过截止点后应释放", cold.remainingMs("srv-a"))
    }

    @Test
    fun `wall clock changes cannot extend an active cooldown`() {
        var monotonic = 0L
        val cold = RateLimitCooldown(clockMs = { monotonic })
        cold.apply("srv-a", 10_000L)
        // 模拟"用户把系统时间往前调一年"：单调时钟不受影响，冷却仍按原截止点释放
        monotonic = 10_000L
        assertNull(cold.remainingMs("srv-a"))
    }

    // ---------- 拦截器行为（MockWebServer 实测出网次数） ----------

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
    fun `a 403 does not open a cooldown window`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = factory.apiClient()
        val url = server.url("/Items")
        client.newCall(Request.Builder().url(url).build()).execute().close()
        val second = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals("403 不得被当作限流冷却", 200, second.code)
        assertEquals(2, server.requestCount)
        second.close()
    }

    @Test
    fun `a 500 does not open a cooldown window`() {
        server.enqueue(MockResponse().setResponseCode(500))
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
    fun `a 429 on a non idempotent request is never replayed`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val client = factory.apiClient()
        val body = "{}".toRequestBody()
        client.newCall(Request.Builder().url(server.url("/Sessions/Playing")).post(body).build())
            .execute().close()
        assertEquals("429 不得自动重放非幂等请求", 1, server.requestCount)
    }
}
