package com.mediahub.metadata.ratings

import com.mediahub.metadata.MetadataMediaType

/** 评分查询所需的跨平台标识（缺失的标识由各来源自行忽略）。 */
data class RatingQuery(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val mediaType: MetadataMediaType? = null,
)

/** 评分来源抽象；实现必须只读、可失败（由 [RatingsService] 隔离）。 */
interface RatingsSource {
    val name: String

    suspend fun ratings(query: RatingQuery): List<PlatformRating>
}
