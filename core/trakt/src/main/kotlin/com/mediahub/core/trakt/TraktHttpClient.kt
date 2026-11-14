package com.mediahub.core.trakt

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 一次 Trakt HTTP 调用的原始结果。 */
internal data class TraktHttpResult(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long?,
)

/**
 * Trakt HTTP 传输层：只负责拼请求与读响应，不做限流与业务判断。
 */
internal class TraktHttpClient(
    private val config: TraktConfig,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): TraktHttpResult = withContext(Dispatchers.IO) {
        val url = (config.baseUrl.trimEnd('/') + path)
            .toHttpUrl()
            .newBuilder()
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("trakt-api-version", "2")
            .header("trakt-api-key", config.clientId.orEmpty())
            .header("User-Agent", "MediaHub")
            .apply {
                config.accessToken?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .method(
                method,
                when {
                    jsonBody != null -> jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                    method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
                    else -> null
                },
            )
            .build()

        client.newCall(request).execute().use { response ->
            TraktHttpResult(
                code = response.code,
                body = response.body?.string().orEmpty(),
                retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 网络异常的可展示摘要（不含 URL/凭据）。 */
internal fun Throwable.toTraktNetworkMessage(): String =
    if (this is IOException) "网络异常，无法连接 Trakt" else "Trakt 请求失败"
