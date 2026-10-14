package com.mediahub.core.network

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.Redactor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 网络层入口：
 * - [apiClient]：普通 API 请求（短超时、幂等请求失败自动重试一次）。
 * - [mediaClient]：媒体流请求（读超时/总超时为 0，避免长视频被掐断；不自动重试）。
 *
 * 两者共用脱敏日志与 X-Request-Id 注入。
 */
class HttpClientFactory(
    private val logger: Logger,
    /**
     * M0-A（ADR-045）：服务器作用域的 UA 策略。默认构造使用 MediaHub 默认 UA，
     * 不读取任何全局可变状态。
     */
    private val userAgentPolicy: UserAgentPolicy = UserAgentPolicy(),
    /** 由请求 URL 解析所属服务器；未知返回 null（跨 origin 跳转回落默认 UA）。 */
    private val serverIdFor: ServerIdResolver = ServerIdResolver { null },
    /** M15：按作用域的 429 冷却（可注入以做确定性测试）。 */
    private val rateLimitCooldown: RateLimitCooldown = RateLimitCooldown(),
) {

    fun apiClient(): OkHttpClient = baseClient()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(RetryOnceInterceptor(logger))
        .build()

    fun mediaClient(): OkHttpClient = baseClient()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        // M15-S3 测试 4：必须是 **network** interceptor（逐跳生效）。
        // OkHttp 的应用拦截器只看到"原始请求 + 最终响应"，其 chain.request().url 永远是入口 URL；
        // 而重定向后真正返回 429 的是**另一跳**（Emby Direct Stream: HTTPS→307→直链IP→对象存储）。
        // 用入口 URL 记账会把第三方跳的 429 误记到原始服务器，造成跨服务器误冷却
        // （M15-S3 测试 4 已复现）。network interceptor 对每一跳生效，chain.request() 即该跳真实
        // URL —— 归属正确，且已冷却的跳在出网前快速失败，不会被反复撞击。
        .addNetworkInterceptor(RateLimitCooldownInterceptor(rateLimitCooldown) { url -> serverIdFor.serverIdFor(url) })
        .addInterceptor(RequestIdInterceptor())
        .addInterceptor(RedactingLoggingInterceptor(logger))
        // M0-A（ADR-045）：network interceptor —— 对每一跳生效，重定向到未知 origin 时
        // 自动回落默认 UA，不把原服务器的自定义 UA 泄漏给第三方域名。只写 UA，不碰认证头。
        .addNetworkInterceptor(UserAgentInterceptor(userAgentPolicy, serverIdFor))
        .eventListenerFactory(StartupNetworkEventListener.FACTORY)

    private class RequestIdInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val withId = if (request.header(HEADER_REQUEST_ID) == null) {
                request.newBuilder().header(HEADER_REQUEST_ID, UUID.randomUUID().toString().take(8)).build()
            } else {
                request
            }
            return chain.proceed(withId)
        }

        companion object {
            const val HEADER_REQUEST_ID = "X-Request-Id"
        }
    }

    /** 对幂等请求（GET/HEAD）在 IOException 时重试一次。 */
    private class RetryOnceInterceptor(private val logger: Logger) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            while (true) {
                attempt++
                try {
                    return chain.proceed(request)
                } catch (e: IOException) {
                    // M15-S3 测试 4：冷却快速失败来自 network interceptor，会穿过本拦截器 ——
                    // 必须显式排除，否则它会被当作普通网络失败重试，造成请求放大
                    // （这正是原设计把冷却放在本拦截器之外的原因，改逐跳后需在此等价保证）。
                    if (e is RateLimitedException) throw e
                    val retriable = request.method in setOf("GET", "HEAD", "DELETE")
                    if (attempt >= MAX_ATTEMPTS || !retriable) throw e
                    logger.w(LogTag.NETWORK, "请求失败，重试 ${attempt + 1}/$MAX_ATTEMPTS: ${request.method} ${Redactor.redact(request.url.toString())}", e)
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }

        private companion object {
            const val MAX_ATTEMPTS = 2
            const val RETRY_DELAY_MS = 300L
        }
    }

    /** 统一脱敏日志（请求行 / 响应行 / 敏感头）。 */
    private class RedactingLoggingInterceptor(private val logger: Logger) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val start = System.nanoTime()
            logger.d(
                LogTag.NETWORK,
                "-> ${request.method} ${Redactor.redact(request.url.toString())} " +
                    "headers=${Redactor.redactHeaders(request.headers.toMap())}"
            )
            val response = chain.proceed(request)
            val ms = (System.nanoTime() - start) / 1_000_000
            logger.d(
                LogTag.NETWORK,
                "<- ${response.code} ${Redactor.redact(request.url.toString())} ${ms}ms " +
                    "requestId=${request.header(RequestIdInterceptor.HEADER_REQUEST_ID)}"
            )
            return response
        }
    }

    companion object {
        const val HEADER_REQUEST_ID = RequestIdInterceptor.HEADER_REQUEST_ID
    }
}
