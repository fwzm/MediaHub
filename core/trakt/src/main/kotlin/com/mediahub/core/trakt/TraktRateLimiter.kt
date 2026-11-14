package com.mediahub.core.trakt

import kotlinx.coroutines.delay

/**
 * Trakt 请求频率限制（条目 142）。
 *
 * 两道闸：
 * 1) 主动限速：两次请求之间至少间隔 [minIntervalMs]；
 * 2) 被动冷却：收到 429 后按 `Retry-After` 暂停后续请求。
 *
 * 时钟与 sleep 均可注入，测试用虚拟时间验证，不真实等待。
 */
class TraktRateLimiter(
    private val minIntervalMs: Long = 350L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var nextAllowedAtMs = 0L
    private var cooldownUntilMs = 0L

    /** 在发起请求前调用；必要时挂起等待。 */
    suspend fun acquire() {
        val waitMs = maxOf(nextAllowedAtMs, cooldownUntilMs) - nowMs()
        if (waitMs > 0) sleep(waitMs)
        nextAllowedAtMs = nowMs() + minIntervalMs
    }

    /** 在收到响应后调用；429 时按 Retry-After 进入冷却。 */
    fun onResponse(code: Int, retryAfterSeconds: Long?) {
        if (code != HTTP_TOO_MANY_REQUESTS) return
        val waitSeconds = (retryAfterSeconds ?: DEFAULT_COOLDOWN_SECONDS).coerceAtLeast(1L)
        cooldownUntilMs = maxOf(cooldownUntilMs, nowMs() + waitSeconds * 1_000L)
    }

    fun cooldownRemainingMs(): Long = (cooldownUntilMs - nowMs()).coerceAtLeast(0L)

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_COOLDOWN_SECONDS = 30L
    }
}
