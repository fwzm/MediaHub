package com.mediahub.metadata.ratings

data class AggregatedRatings(
    val ratings: List<PlatformRating>,
    val average: Double?,
) {
    fun byPlatform(platform: RatingPlatform): PlatformRating? = ratings.firstOrNull { it.platform == platform }
}

/**
 * 评分聚合：按来源顺序去重（先到先得，用于表达来源优先级），
 * 再把可用平台统一换算到 10 分制求平均；无评分时平均值为 null。
 */
class RatingsAggregator {

    fun aggregate(groups: List<List<PlatformRating>>): AggregatedRatings {
        val byPlatform = LinkedHashMap<RatingPlatform, PlatformRating>()
        groups.forEach { group ->
            group.forEach { rating ->
                byPlatform.putIfAbsent(rating.platform, rating)
            }
        }
        val ratings = byPlatform.values.toList()
        val average = ratings.takeIf { it.isNotEmpty() }?.map { it.normalizedScore }?.average()
        return AggregatedRatings(ratings = ratings, average = average)
    }
}
