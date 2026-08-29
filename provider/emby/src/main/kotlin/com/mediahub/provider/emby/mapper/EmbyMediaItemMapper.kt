package com.mediahub.provider.emby.mapper
import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.UserData
import com.mediahub.provider.emby.api.EmbyItemFields
/**
 * Emby BaseItem → 领域 MediaItem（Phase 1B-1 浏览；Phase 1B-2 起同时服务详情）。
 * Type/MediaType/IsFolder → 统一 MediaType，协议差异止步于此。
 */
object EmbyMediaItemMapper {
    /** Emby RunTimeTicks 单位：100 纳秒；1 秒 = 10_000_000 ticks。 */
    private const val TICKS_PER_MILLIS = 10_000L

    /**
     * ProviderIds 字典 → [ExternalIds]（Phase 1E 归一化，冻结规则）：
     * key trim+lowercase（wire 键如 "Imdb"/"TMDB" 大小写不敏感）、value trim、
     * 空白键值跳过；**同一 provider 出现冲突值时整个 provider 丢弃**
     * （不做 first/last wins，避免 map 迭代顺序制造错误跨源聚合）。
     * 全部无效返回 null。
     */
    private fun mapExternalIds(raw: Map<String, String>?): ExternalIds? {
        if (raw.isNullOrEmpty()) return null
        val normalized = mutableMapOf<String, String>()
        val conflicted = mutableSetOf<String>()
        raw.forEach { (key, value) ->
            val k = key.trim().lowercase()
            val v = value.trim()
            if (k.isEmpty() || v.isEmpty() || k in conflicted) return@forEach
            val existing = normalized[k]
            if (existing != null && existing != v) {
                normalized.remove(k)
                conflicted += k
            } else {
                normalized[k] = v
            }
        }
        if (normalized.isEmpty()) return null
        return ExternalIds(
            tmdb = normalized["tmdb"],
            imdb = normalized["imdb"],
            tvdb = normalized["tvdb"],
        )
    }

    /**
     * preflight 加固（Phase 1B-2）：Id 缺失/空白的条目禁止进入领域层
     * （会破坏详情/播放/进度/继续观看链路），返回 null 由调用方过滤或报错。
     */
    fun map(dto: EmbyItemFields, serverId: String): MediaItem? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
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
            overview = dto.overview,
            genres = dto.genres,
            year = dto.productionYear,
            runtimeMs = dto.runTimeTicks?.div(TICKS_PER_MILLIS),
            communityRating = dto.communityRating,
            criticRating = dto.criticRating,
            officialRating = dto.officialRating,
            premiereDateEpochMs = EmbyDateTimes.parseEpochMs(dto.premiereDate),
            dateAddedEpochMs = EmbyDateTimes.parseEpochMs(dto.dateCreated),
            container = dto.container,
            sizeBytes = dto.size,
            bitrate = dto.bitrate,
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
            sortName = dto.sortName?.takeIf(String::isNotBlank) ?: title,
            externalIds = mapExternalIds(dto.providerIds),
        )
    }
    fun mapType(dto: EmbyItemFields): MediaType = when (dto.type?.lowercase()) {
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
