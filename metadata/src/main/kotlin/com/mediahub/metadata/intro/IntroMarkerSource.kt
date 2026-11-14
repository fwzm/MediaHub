package com.mediahub.metadata.intro

/** 片头 / 片尾标记类型。 */
enum class MarkerType {
    INTRO,
    CREDITS,
}

/** 片头片尾查询条件；[runtimeMs] 允许为 null（条目 138：缺时长也必须可用）。 */
data class MarkerQuery(
    val title: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMs: Long? = null,
)

/**
 * 一条片头/片尾标记，时间均为绝对毫秒。
 *
 * [contributors] 记录提供该标记的全部来源，供 UI 显示"标记来源"（条目 127）；
 * [source] 为最终采用的主来源。
 */
data class MediaMarker(
    val type: MarkerType,
    val startMs: Long,
    val endMs: Long? = null,
    val source: String,
    val confidence: Double = 0.5,
    val contributors: Set<String> = setOf(source),
)

data class MarkerFailure(
    val source: String,
    val statusCode: Int?,
    val message: String,
)

data class MarkerResolution(
    val markers: List<MediaMarker>,
    val failures: List<MarkerFailure>,
)

/**
 * 片头片尾来源抽象。实现必须返回绝对时间戳，不得依赖媒体时长推算，
 * 这样在 [MarkerQuery.runtimeMs] 缺失时仍能保持准确。
 */
interface IntroMarkerSource {
    val name: String
    val priority: Int

    suspend fun markers(query: MarkerQuery): List<MediaMarker>
}
