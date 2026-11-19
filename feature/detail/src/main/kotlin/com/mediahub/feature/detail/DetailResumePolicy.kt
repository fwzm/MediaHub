package com.mediahub.feature.detail

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType

/**
 * #13 详情页继续观看定位策略：从剧集进度判断“当前集”，再反查所属季。
 * 纯函数，便于 JVM 单测；不依赖服务器额外字段（UserData 已由 provider 映射）。
 */
internal object DetailResumePolicy {
    /** 已看阈值：与详情行“已看”判定保持一致。 */
    const val WATCHED_THRESHOLD = 95.0

    fun isInProgress(item: MediaItem): Boolean {
        if (item.type != MediaType.EPISODE) return false
        val userData = item.userData ?: return false
        val percentage = userData.playedPercentage
        val hasProgress = (userData.playbackPositionMs ?: 0L) > 0L ||
            (percentage != null && percentage > 0.0)
        return hasProgress && (percentage ?: 0.0) < WATCHED_THRESHOLD
    }

    fun currentEpisodeId(episodes: List<MediaItem>): String? =
        episodes.firstOrNull { isInProgress(it) }?.id

    fun findInProgressSeason(
        seasons: List<MediaItem>,
        episodesBySeason: Map<String, List<MediaItem>>,
    ): MediaItem? = seasons.firstOrNull { season ->
        episodesBySeason[season.id].orEmpty().any { isInProgress(it) }
    }
}
