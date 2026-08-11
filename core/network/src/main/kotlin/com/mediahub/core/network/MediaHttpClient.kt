package com.mediahub.core.network

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.Redactor
import com.mediahub.model.PlaybackSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 媒体流网络层。与普通 API 客户端（[ApiClient]）分离：
 * - 支持 Range / 206 / 302 / Cookie / 鉴权头；
 * - 播放前探测（[probe]）把"播放失败"结构化为 [PlaybackError]；
 * - 返回的 [OkHttpClient] 供播放器（Media3 OkHttpDataSource）复用连接池。
 */
class MediaHttpClient(
    private val client: OkHttpClient,
    private val logger: Logger,
) {

    /** 播放前探测：HEAD 或 Range=bytes=0-0，验证链接、是否支持 Seek、测延迟。 */
    suspend fun probe(source: PlaybackSource): MediaProbeResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val request = Request.Builder()
                .url(source.url)
                .header("Range", "bytes=0-0")
                .apply { source.headers.forEach { (k, v) -> header(k, v) } }
                .apply {
                    val cookie = buildCookieHeader(source.cookies)
                    if (cookie != null) header("Cookie", cookie)
                }
                .build()

            client.newCall(request).execute().use { response ->
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                when {
                    response.isSuccessful -> {
                        val supportsRange = response.code == 206 ||
                            (response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true)
                        MediaProbeResult.Success(
                            finalUrl = response.request.url.toString(),
                            httpCode = response.code,
                            supportsRange = supportsRange,
                            contentLength = response.body?.contentLength()?.takeIf { it >= 0 },
                            contentType = response.header("Content-Type"),
                            latencyMs = latencyMs,
                        )
                    }

                    else -> MediaProbeResult.Failure(
                        PlaybackErrorMapper.fromHttpStatus(response.code, source.url)
                    )
                }
            }
        } catch (e: IOException) {
            logger.w(LogTag.NETWORK, "媒体探测失败 url=${Redactor.redact(source.url)}", e)
            MediaProbeResult.Failure(PlaybackErrorMapper.fromIoException(e))
        }
    }

    /** 供播放器（Media3 DataSource）使用的连接池客户端。 */
    fun okHttpClient(): OkHttpClient = client

    private fun buildCookieHeader(cookies: Map<String, String>): String? =
        cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }.takeIf { it.isNotBlank() }
}

/** 播放前探测结果。 */
sealed interface MediaProbeResult {
    data class Success(
        val finalUrl: String,
        val httpCode: Int,
        val supportsRange: Boolean,
        val contentLength: Long? = null,
        val contentType: String? = null,
        val latencyMs: Long,
    ) : MediaProbeResult

    data class Failure(val error: PlaybackError) : MediaProbeResult
}
