package com.mediahub.core.trakt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目 142：Trakt 请求频率限制（主动间隔 + 429 冷却），用虚拟时间验证，不真实等待。
 */
class TraktRateLimiterTest {

    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private val limiter = TraktRateLimiter(
        minIntervalMs = 350L,
        nowMs = { now },
        sleep = { ms ->
            sleeps += ms
            now += ms
        },
    )

    @Test
    fun `subsequent requests wait for the minimum interval`() = runBlocking {
        limiter.acquire()
        assertTrue(sleeps.isEmpty())

        now += 100
        limiter.acquire()
        assertEquals(listOf(250L), sleeps)
    }

    @Test
    fun `retry after sets a cooldown that the next acquire honors`() = runBlocking {
        limiter.acquire()
        limiter.onResponse(429, retryAfterSeconds = 60)
        assertEquals(60_000L, limiter.cooldownRemainingMs())

        limiter.acquire()

        assertEquals(listOf(60_000L), sleeps)
        assertEquals(0L, limiter.cooldownRemainingMs())
    }

    @Test
    fun `non rate limited responses do not start a cooldown`() {
        limiter.onResponse(200, retryAfterSeconds = null)
        assertEquals(0L, limiter.cooldownRemainingMs())
    }
}
