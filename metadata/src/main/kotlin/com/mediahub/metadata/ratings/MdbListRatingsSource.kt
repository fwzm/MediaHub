package com.mediahub.metadata.ratings

import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * MDBList 聚合评分来源：一次请求拿回 IMDb / TMDB / 烂番茄 / Letterboxd / Trakt / Metacritic。
 *
 * API Key 由调用方注入；测试用 MockWebServer，不访问真实服务。
 * 未知来源与空值被丢弃，避免把占位 0 分混入平均分。
 */
class MdbListRatingsSource(
    private val http: MetadataHttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = DEFAULT_JSON,
) : RatingsSource {

    override val name: String = "MDBList"

    private val apiKey: String = apiKey.trim()
    private val root: String = baseUrl.trimEnd('/')

    init {
        require(this.apiKey.isNotEmpty()) { "MDBList API Key 不能为空" }
    }

    override suspend fun ratings(query: RatingQuery): List<PlatformRating> {
        val imdbId = query.imdbId?.trim().orEmpty()
        if (imdbId.isEmpty()) return emptyList()
        val url = "$root/imdb/$imdbId".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", apiKey)
            .build()
        val body = http.get(url.toString())
        val response = try {
            json.decodeFromString(MdbListResponse.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MetadataException(null, "评分响应解析失败", e)
        }
        return response.ratings.mapNotNull { item ->
            val value = item.value ?: return@mapNotNull null
            val (platform, scale) = platformOf(item.source) ?: return@mapNotNull null
            PlatformRating(
                platform = platform,
                score = value,
                scale = scale,
                votes = item.votes,
            )
        }
    }

    private fun platformOf(source: String): Pair<RatingPlatform, Int>? = when (source.lowercase()) {
        "imdb" -> RatingPlatform.IMDB to 10
        "tmdb" -> RatingPlatform.TMDB to 10
        "trakt" -> RatingPlatform.TRAKT to 10
        "letterboxd" -> RatingPlatform.LETTERBOXD to 5
        "tomatoes", "rottentomatoes", "rotten_tomatoes" -> RatingPlatform.ROTTEN_TOMATOES to 100
        "metacritic" -> RatingPlatform.METACRITIC to 100
        else -> null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mdblist.com"

        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class MdbListResponse(
    val ratings: List<MdbListItem> = emptyList(),
)

@Serializable
private data class MdbListItem(
    val source: String = "",
    val value: Double? = null,
    val votes: Long? = null,
    @SerialName("url") val url: String? = null,
)
