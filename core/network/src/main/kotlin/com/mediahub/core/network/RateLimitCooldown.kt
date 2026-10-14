package com.mediahub.core.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 429 冷却的可识别失败（M15）。继承 IOException 以便既有"网络失败"路径统一处理，
 * 但**不携带任何 URL / token / 账号**；只保留脱敏作用域与剩余时长。
 */
class RateLimitedException(val scope: String, val remainingMs: Long) :
    IOException("rate limited (scope=$scope, remainingMs=$remainingMs)")

/**
 * 按作用域的有界冷却（M15-A）。
 *
 * - 使用**单调时钟**计冷却，避免设备时间变化导致负冷却；
 * - 冷却时长有**上限**（maxCooldownMs），并拒绝负值/过去时间（视为无冷却）；
 * - 作用域由调用方给出（真实 serverId 优先，未知时退化为 origin），保证 A 服 429
 *   不会被施加到 B 服；
 * - 不做任何 sleep：窗口内直接**快速失败**，绝不阻塞拦截器线程。
 */
class RateLimitCooldown(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
) {
    private val deadlines = ConcurrentHashMap<String, Long>()

    /** 剩余冷却毫秒；不在冷却中返回 null。 */
    fun remainingMs(scope: String): Long? {
        val deadline = deadlines[scope] ?: return null
        val remaining = deadline - clockMs()
        if (remaining <= 0L) {
            deadlines.remove(scope, deadline)
            return null
        }
        return remaining
    }

    fun apply(scope: String, retryAfterMs: Long) {
        if (retryAfterMs <= 0L) {
            deadlines.remove(scope)
            return
        }
        deadlines[scope] = clockMs() + retryAfterMs.coerceAtMost(maxCooldownMs)
    }

    fun clear() = deadlines.clear()

    companion object {
        const val MAX_COOLDOWN_MS: Long = 15 * 60 * 1000L

        /** Retry-After 缺失/非法时的保守默认值（仍受上限约束）。 */
        const val DEFAULT_COOLDOWN_MS: Long = 60 * 1000L

        /**
         * 解析 Retry-After：delta-seconds 或 HTTP-date。
         * - 数字：秒转毫秒（0 或负值表示无冷却）
         * - HTTP-date：与 nowEpochMs 比较；过去时间返回 0
         * - 缺失/格式错误：[DEFAULT_COOLDOWN_MS]
         */
        fun parseRetryAfter(header: String?, nowEpochMs: Long): Long {
            val raw = header?.trim().orEmpty()
            if (raw.isEmpty()) return DEFAULT_COOLDOWN_MS
            raw.toLongOrNull()?.let { seconds ->
                return if (seconds <= 0L) 0L else seconds * 1000L
            }
            return runCatching {
                val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                val delta = target - nowEpochMs
                if (delta <= 0L) 0L else delta
            }.getOrDefault(DEFAULT_COOLDOWN_MS)
        }
    }
}

/**
 * 429 冷却拦截器：窗口内快速失败；收到 429 时按作用域记录冷却。
 *
 * 注册位置必须在 RetryOnceInterceptor **之外**（baseClient 早于 apiClient 追加），
 * 否则冷却失败会被当作可重试的 IOException 造成请求放大。
 */
class RateLimitCooldownInterceptor(
    private val cooldown: RateLimitCooldown,
    private val scopeOf: (HttpUrl) -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = scopeOf(request.url) ?: request.url.host
        cooldown.remainingMs(scope)?.let { remaining ->
            throw RateLimitedException(scope = scope, remainingMs = remaining)
        }
        val response = chain.proceed(request)
        if (response.code == 429) {
            val retryAfterMs = RateLimitCooldown.parseRetryAfter(
                header = response.header("Retry-After"),
                nowEpochMs = System.currentTimeMillis(),
            )
            cooldown.apply(scope, retryAfterMs)
        }
        return response
    }
}
