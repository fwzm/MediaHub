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
class HttpClientFactory(private val logger: Logger) {

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
        .addInterceptor(RequestIdInterceptor())
        .addInterceptor(RedactingLoggingInterceptor(logger))
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
