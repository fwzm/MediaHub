package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.UserData
import com.mediahub.provider.emby.api.EmbyBaseItemDto

/**
 * Emby BaseItem → 领域 MediaItem（Phase 1B-1 浏览）。
 * Type/MediaType/IsFolder → 统一 MediaType，协议差异止步于此。
 */
object EmbyMediaItemMapper {

    /** Emby RunTimeTicks 单位：100 纳秒；1 秒 = 10_000_000 ticks。 */
    private const val TICKS_PER_MILLIS = 10_000L

    fun map(dto: EmbyBaseItemDto, serverId: String): MediaItem {
        val id = dto.id.orEmpty()
        val title = dto.name?.takeIf(String::isNotBlank) ?: id
        val type = mapType(dto)
        return MediaItem(
            serverId = serverId,
            id = id,
            type = type,
            title = title,
            parentId = dto.parentId,
            seriesId = dto.seriesId,
            seasonId = dto.seasonId,
            seasonNumber = when (type) {
                MediaType.SEASON -> dto.indexNumber
                MediaType.EPISODE -> dto.parentIndexNumber
                else -> null
            },
            episodeNumber = if (type == MediaType.EPISODE) dto.indexNumber else null,
            year = dto.productionYear,
            runtimeMs = dto.runTimeTicks?.div(TICKS_PER_MILLIS),
            isFavorite = dto.userData?.isFavorite ?: false,
            playCount = dto.userData?.playCount ?: 0,
            userData = dto.userData?.let { u ->
                UserData(
                    playCount = u.playCount ?: 0,
                    isFavorite = u.isFavorite,
                    playedPercentage = u.playedPercentage,
                    playbackPositionMs = u.playbackPositionTicks?.div(TICKS_PER_MILLIS),
                )
            },
        )
    }

    fun mapType(dto: EmbyBaseItemDto): MediaType = when (dto.type?.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.SERIES
        "season" -> MediaType.SEASON
        "episode" -> MediaType.EPISODE
        "audio" -> MediaType.AUDIO
        "video", "musicvideo" -> MediaType.VIDEO
        "folder" -> MediaType.FOLDER
        else -> {
            // 安全 fallback：IsFolder → FOLDER；否则按 MediaType；最后 OTHER
            when {
                dto.isFolder -> MediaType.FOLDER
                dto.mediaType?.lowercase() == "video" -> MediaType.VIDEO
                dto.mediaType?.lowercase() == "audio" -> MediaType.AUDIO
                else -> MediaType.OTHER
            }
        }
    }
}
