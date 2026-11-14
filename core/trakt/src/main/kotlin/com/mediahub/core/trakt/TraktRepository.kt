package com.mediahub.core.trakt

import com.mediahub.core.database.repository.RoomTraktWatchlistRepository
import com.mediahub.core.database.repository.TraktWatchlistStore
import com.mediahub.model.TraktCalendarEntry
import com.mediahub.model.TraktResult
import com.mediahub.model.TraktShow
import com.mediahub.model.TraktTrackedShow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trakt 追更仓库（条目 142/146）。
 *
 * 约定：
 * - 未配置 client id / access token 时返回 [TraktResult.NotConfigured] 或明确文案，
 *   **绝不发起网络请求**，也不抛异常；
 * - 所有请求先过 [TraktRateLimiter]（主动间隔 + 429 冷却）；
 * - 写操作只有在 Trakt 成功后才会更新本地追更表，避免状态漂移。
 */
@Singleton
class TraktRepository internal constructor(
    private val config: TraktConfig,
    private val watchlist: TraktWatchlistStore,
    private val rateLimiter: TraktRateLimiter,
    private val http: TraktHttpClient,
    private val clockMs: () -> Long,
) {
    @Inject
    constructor(
        config: TraktConfig,
        watchlist: RoomTraktWatchlistRepository,
    ) : this(
        config = config,
        watchlist = watchlist,
        rateLimiter = TraktRateLimiter(config.minRequestIntervalMs),
        http = TraktHttpClient(config),
        clockMs = System::currentTimeMillis,
    )

    val isConfigured: Boolean get() = config.canRead
    val canWrite: Boolean get() = config.canWrite

    fun observeTrackedShows(): Flow<List<TraktTrackedShow>> = watchlist.observeTracked()

    suspend fun trackedShows(): List<TraktTrackedShow> = watchlist.allTracked()

    suspend fun isTracked(traktId: Int): Boolean = watchlist.isTracked(traktId)

    suspend fun searchShow(title: String, year: Int?): TraktResult<TraktShow> {
        if (!config.canRead) return TraktResult.NotConfigured
        val response = call(
            method = "GET",
            path = "/search/show",
            query = mapOf(
                "query" to title,
                "limit" to SEARCH_LIMIT.toString(),
            ),
        )
        if (!response.isSuccess) return response.toFailure()

        val items = runCatching {
            json.decodeFromString<List<TraktSearchItemDto>>(response.body)
        }.getOrElse { return TraktResult.Failure("Trakt 返回数据无法解析") }

        val show = items.asSequence()
            .mapNotNull { it.show?.toModel() }
            .let { candidates ->
                if (year == null) candidates.firstOrNull()
                else candidates.firstOrNull { it.year == year } ?: candidates.firstOrNull()
            }
        return if (show != null) TraktResult.Success(show) else TraktResult.Failure("Trakt 未找到该剧集")
    }

    suspend fun addToWatchlist(show: TraktShow): TraktResult<Unit> {
        if (!config.canRead) return TraktResult.NotConfigured
        if (!config.canWrite) return TraktResult.Failure("未配置 Trakt 访问令牌，无法加入追更")
        val response = call(
            method = "POST",
            path = "/sync/watchlist",
            jsonBody = watchlistBody(show.traktId),
        )
        if (!response.isSuccess) return response.toFailure()
        watchlist.add(
            TraktTrackedShow(
                traktId = show.traktId,
                title = show.title,
                year = show.year,
                addedAtEpochMs = clockMs(),
            ),
        )
        return TraktResult.Success(Unit)
    }

    suspend fun removeFromWatchlist(traktId: Int): TraktResult<Unit> {
        if (!config.canRead) return TraktResult.NotConfigured
        if (!config.canWrite) return TraktResult.Failure("未配置 Trakt 访问令牌，无法取消追更")
        val response = call(
            method = "POST",
            path = "/sync/watchlist/remove",
            jsonBody = watchlistBody(traktId),
        )
        if (!response.isSuccess) return response.toFailure()
        watchlist.remove(traktId)
        return TraktResult.Success(Unit)
    }

    /** 追更日历：默认从 [startDate] 起 [days] 天（含当天）。 */
    suspend fun getCalendar(startDate: LocalDate, days: Int = 14): TraktResult<List<TraktCalendarEntry>> {
        if (!config.canRead) return TraktResult.NotConfigured
        if (!config.canWrite) return TraktResult.Failure("未配置 Trakt 访问令牌，无法加载追更日历")
        val safeDays = days.coerceIn(1, 33)
        val response = call(
            method = "GET",
            path = "/calendars/my/shows/$startDate/$safeDays",
        )
        if (!response.isSuccess) return response.toFailure()

        val items = runCatching {
            json.decodeFromString<List<TraktCalendarItemDto>>(response.body)
        }.getOrElse { return TraktResult.Failure("Trakt 返回数据无法解析") }

        val entries = items.mapNotNull { item ->
            val id = item.show.ids.trakt ?: return@mapNotNull null
            TraktCalendarEntry(
                traktId = id,
                showTitle = item.show.title,
                season = item.episode.season,
                episode = item.episode.number,
                episodeTitle = item.episode.title,
                firstAired = item.firstAired.take(10),
            )
        }
        return TraktResult.Success(entries)
    }

    /**
     * 发起一次受控请求；网络异常映射为 code=0 的失败结果。
     */
    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): TraktHttpResult {
        rateLimiter.acquire()
        val result = try {
            http.execute(method, path, query, jsonBody)
        } catch (e: Exception) {
            return TraktHttpResult(code = 0, body = e.toTraktNetworkMessage(), retryAfterSeconds = null)
        }
        rateLimiter.onResponse(result.code, result.retryAfterSeconds)
        return result
    }

    private fun TraktHttpResult.toFailure(): TraktResult.Failure = when (code) {
        HTTP_TOO_MANY_REQUESTS -> TraktResult.Failure(
            message = "Trakt 请求过于频繁，请 ${retryAfterSeconds ?: 30} 秒后重试",
            retryAfterSeconds = retryAfterSeconds ?: 30,
        )
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> TraktResult.Failure("Trakt 授权失效，请重新登录")
        HTTP_NOT_FOUND -> TraktResult.Failure("Trakt 未找到对应条目")
        0 -> TraktResult.Failure(body.ifBlank { "网络异常，无法连接 Trakt" })
        else -> TraktResult.Failure("Trakt 请求失败（HTTP $code）")
    }

    private fun watchlistBody(traktId: Int): String =
        """{"shows":[{"ids":{"trakt":$traktId}}]}"""

    private fun TraktShowDto.toModel(): TraktShow? {
        val id = ids.trakt ?: return null
        return TraktShow(traktId = id, title = title, year = year, slug = ids.slug)
    }

    private val TraktHttpResult.isSuccess: Boolean
        get() = code in 200..299

    private companion object {
        const val SEARCH_LIMIT = 5
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class TraktIdsDto(
    val trakt: Int? = null,
    val slug: String? = null,
)

@Serializable
internal data class TraktShowDto(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIdsDto = TraktIdsDto(),
)

@Serializable
internal data class TraktSearchItemDto(
    val show: TraktShowDto? = null,
)

@Serializable
internal data class TraktEpisodeDto(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
)

@Serializable
internal data class TraktCalendarItemDto(
    @SerialName("first_aired") val firstAired: String = "",
    val episode: TraktEpisodeDto = TraktEpisodeDto(),
    val show: TraktShowDto = TraktShowDto(),
)
