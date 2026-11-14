package com.mediahub.core.danmaku

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 弹弹play 开放 API 配置；凭据未配置时在线源保持关闭且给出明确错误。 */
data class DanDanPlayConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val appId: String = "",
    val appSecret: String = "",
) {
    val isConfigured: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.dandanplay.net"
    }
}

/**
 * 弹弹play 客户端：只负责 HTTP 与 JSON 形状转换，不做缓存与降级（在仓库层完成）。
 * 所有网络调用可取消；非 2xx 抛 [DanmakuSourceException]。
 */
class DanDanPlayClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun searchEpisodes(config: DanDanPlayConfig, keyword: String): List<DanmakuEpisode> {
        if (keyword.isBlank()) return emptyList()
        val url = buildUrl(
            config = config,
            segments = listOf("api", "v2", "search", "episodes"),
            query = mapOf("anime" to keyword.trim(), "episode" to ""),
        )
        val body = execute(config, url)
        return parseEpisodes(body)
    }

    suspend fun fetchComments(
        config: DanDanPlayConfig,
        episodeId: String,
        withRelated: Boolean = true,
    ): List<DanmakuComment> {
        if (episodeId.isBlank()) return emptyList()
        val url = buildUrl(
            config = config,
            segments = listOf("api", "v2", "comment", episodeId),
            query = mapOf("withRelated" to withRelated.toString(), "chConvert" to "0"),
        )
        return DanmakuParser.parseDanDanPlayJson(execute(config, url))
    }

    private fun buildUrl(
        config: DanDanPlayConfig,
        segments: List<String>,
        query: Map<String, String>,
    ): HttpUrl {
        val base = config.baseUrl.ifBlank { DanDanPlayConfig.DEFAULT_BASE_URL }
        val root = base.toHttpUrlOrNull()
            ?: throw DanmakuSourceException("弹弹play 地址无效：$base")
        val builder = root.newBuilder()
        segments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private suspend fun execute(config: DanDanPlayConfig, url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (config.appId.isNotBlank()) header("X-AppId", config.appId)
                if (config.appSecret.isNotBlank()) header("X-AppSecret", config.appSecret)
            }
            .build()
        val response = httpClient.newCall(request).await()
        response.use { result ->
            if (!result.isSuccessful) {
                throw DanmakuSourceException("弹弹play HTTP ${result.code}")
            }
            return result.body?.string().orEmpty()
        }
    }

    private fun parseEpisodes(body: String): List<DanmakuEpisode> {
        return try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val array = root["episodes"] as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.string("episodeId") ?: return@mapNotNull null
                DanmakuEpisode(
                    id = id,
                    animeTitle = obj.string("animeTitle").orEmpty(),
                    episodeTitle = obj.string("episodeTitle").orEmpty(),
                    episodeNumber = (obj["episodeNumber"] as? JsonPrimitive)?.intOrNull,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            },
        )
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }
}

/**
 * 弹弹play 数据源：搜索剧集 → 取该剧集全片弹幕。
 * 匹配不到剧集时显式失败，避免把别的番的弹幕贴到当前视频上。
 */
class DanDanPlaySource(
    private val client: DanDanPlayClient,
) : DanmakuSource, DanmakuEpisodeSearch, DanmakuEpisodeCommentSource {

    override val label: String = "DanDanPlay"

    override suspend fun fetch(query: DanmakuQuery): List<DanmakuComment> {
        val config = query.danDanPlay
        if (!config.isConfigured) {
            throw DanmakuSourceException("未配置 DanDanPlay AppId/AppSecret")
        }
        val episodes = client.searchEpisodes(config, query.seriesTitle)
        if (episodes.isEmpty()) throw DanmakuSourceException("DanDanPlay 未找到「${query.seriesTitle}」")
        val matched = selectEpisode(episodes, query)
            ?: throw DanmakuSourceException("DanDanPlay 未匹配到对应剧集")
        return client.fetchComments(config, matched.id)
    }

    override suspend fun searchEpisodes(
        config: DanDanPlayConfig,
        keyword: String,
    ): List<DanmakuEpisode> {
        if (!config.isConfigured) {
            throw DanmakuSourceException("未配置 DanDanPlay AppId/AppSecret")
        }
        return client.searchEpisodes(config, keyword)
    }

    override suspend fun fetchEpisode(
        config: DanDanPlayConfig,
        episodeId: String,
    ): List<DanmakuComment> {
        if (!config.isConfigured) {
            throw DanmakuSourceException("未配置 DanDanPlay AppId/AppSecret")
        }
        return client.fetchComments(config, episodeId)
    }

    private fun selectEpisode(
        episodes: List<DanmakuEpisode>,
        query: DanmakuQuery,
    ): DanmakuEpisode? {
        val desired = query.episodeTitle?.trim().orEmpty()
        if (desired.isEmpty()) return episodes.first()
        return episodes.firstOrNull { it.episodeTitle.contains(desired, ignoreCase = true) }
            ?: episodes.firstOrNull { desired.contains(it.episodeTitle, ignoreCase = true) }
            ?: run {
                val desiredNumber = NUMBER.find(desired)?.value?.toIntOrNull()
                episodes.firstOrNull { it.episodeNumber == desiredNumber }
            }
            ?: episodes.first()
    }

    private companion object {
        val NUMBER = Regex("""\d{1,4}""")
    }
}
