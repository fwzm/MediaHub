package com.mediahub.metadata.tmdb

import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * TMDB v3 API 的最小客户端（搜索 / 详情 / 季集）。
 *
 * 不保留任何默认密钥：apiKey 由调用方注入（生产走设置项/密钥存储，测试走 MockWebServer）。
 * 异常消息不包含 URL，避免 api_key 随异常文本外泄。
 */
internal class TmdbApi internal constructor(
    private val http: MetadataHttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val language: String = "zh-CN",
    private val json: Json = DEFAULT_JSON,
) {

    private val apiKey: String = apiKey.trim()
    private val root: String = baseUrl.trimEnd('/')

    init {
        require(this.apiKey.isNotEmpty()) { "TMDB API Key 不能为空" }
    }

    suspend fun search(query: String): TmdbSearchResponse =
        request("$root/search/multi") {
            addQueryParameter("query", query)
            addQueryParameter("include_adult", "false")
        }

    suspend fun detail(kind: TmdbMediaKind, id: Int): TmdbDetail =
        request("$root/${kind.path}/$id") {
            addQueryParameter("append_to_response", "credits,external_ids")
        }

    suspend fun season(tvId: Int, seasonNumber: Int): TmdbSeasonDetail =
        request("$root/tv/$tvId/season/$seasonNumber")

    private suspend inline fun <reified T> request(
        path: String,
        crossinline configure: okhttp3.HttpUrl.Builder.() -> Unit = {},
    ): T {
        val url = path.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", language)
            .apply(configure)
            .build()
        val body = http.get(url.toString())
        return decode(body, kotlinx.serialization.serializer<T>())
    }

    private fun <T> decode(body: String, deserializer: DeserializationStrategy<T>): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MetadataException(null, "元数据响应解析失败", e)
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"

        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
