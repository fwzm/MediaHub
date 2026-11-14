package com.mediahub.metadata.ratings

import java.util.Locale

/** 多平台评分来源（条目 115）。 */
enum class RatingPlatform(val label: String) {
    IMDB("IMDb"),
    TMDB("TMDB"),
    ROTTEN_TOMATOES("烂番茄"),
    LETTERBOXD("Letterboxd"),
    TRAKT("Trakt"),
    METACRITIC("Metacritic"),
}

/**
 * 单个平台评分。各平台原生量纲不同（10 分 / 5 星 / 百分比 / 100 分），
 * 保留 [score] + [scale] 以便按平台习惯展示，并统一换算到 10 分制做聚合。
 */
data class PlatformRating(
    val platform: RatingPlatform,
    val score: Double,
    val scale: Int = 10,
    val votes: Long? = null,
    val url: String? = null,
) {
    init {
        require(scale > 0) { "评分量纲必须为正数：$scale" }
    }

    val normalizedScore: Double get() = (score / scale * 10.0).coerceIn(0.0, 10.0)

    val display: String
        get() = when (platform) {
            RatingPlatform.ROTTEN_TOMATOES -> "${format(score)}%"
            RatingPlatform.METACRITIC -> "${format(score)}/100"
            RatingPlatform.LETTERBOXD -> "${format(score)}/5"
            else -> "${format(score)}/10"
        }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}
