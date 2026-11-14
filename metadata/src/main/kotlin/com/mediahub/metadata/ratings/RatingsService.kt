package com.mediahub.metadata.ratings

import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataException

data class RatingFailure(
    val source: String,
    val statusCode: Int?,
    val message: String,
)

data class RatingsOutcome(
    val ratings: AggregatedRatings,
    val failures: List<RatingFailure>,
)

/**
 * 多平台评分服务：TMDB 详情自带评分直接入组（最高优先级），
 * 其余来源按注入顺序查询；单源失败只记录，不影响其它平台与总分。
 */
class RatingsService(
    private val sources: List<RatingsSource>,
    private val aggregator: RatingsAggregator = RatingsAggregator(),
) {

    suspend fun load(detail: MetadataDetail): RatingsOutcome {
        val groups = mutableListOf<List<PlatformRating>>()
        detail.rating?.let { groups += listOf(PlatformRating(RatingPlatform.TMDB, it, 10)) }

        val query = RatingQuery(
            imdbId = detail.externalIds[IMDB_KEY],
            tmdbId = detail.externalIds[TMDB_KEY],
            mediaType = detail.mediaType,
        )
        val failures = mutableListOf<RatingFailure>()
        for (source in sources) {
            try {
                groups += source.ratings(query)
            } catch (e: MetadataException) {
                failures += RatingFailure(source.name, e.statusCode, e.message.orEmpty())
            }
        }
        return RatingsOutcome(aggregator.aggregate(groups), failures)
    }

    private companion object {
        const val IMDB_KEY = "imdb"
        const val TMDB_KEY = "tmdb"
    }
}
