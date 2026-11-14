package com.mediahub.model

/** Trakt 剧集摘要（只保留本仓库需要的字段）。 */
data class TraktShow(
    val traktId: Int,
    val title: String,
    val year: Int?,
    val slug: String? = null,
)

/** Trakt 追更日历中的一集。 */
data class TraktCalendarEntry(
    val traktId: Int,
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    /** ISO-8601 日期（Trakt 返回 first_aired 的日期部分）。 */
    val firstAired: String,
)

/** 本地已追更的剧集。 */
data class TraktTrackedShow(
    val traktId: Int,
    val title: String,
    val year: Int?,
    val addedAtEpochMs: Long,
)

/**
 * Trakt 调用结果（条目 142/146）。
 *
 * - [NotConfigured]：未注入凭据，直接降级，不发起网络请求、不崩溃；
 * - [Failure]：带可展示的中文原因与可选的重试等待秒数（限流时）。
 */
sealed interface TraktResult<out T> {
    data class Success<T>(val value: T) : TraktResult<T>
    data class Failure(
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : TraktResult<Nothing>

    data object NotConfigured : TraktResult<Nothing>
}
