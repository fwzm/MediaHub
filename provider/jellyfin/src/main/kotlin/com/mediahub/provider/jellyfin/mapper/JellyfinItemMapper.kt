package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.Person
import com.mediahub.model.UserData
import com.mediahub.provider.jellyfin.api.JellyfinItemDto
import com.mediahub.provider.jellyfin.api.JellyfinPersonDto

/**
 * Jellyfin BaseItemDto → 领域 MediaItem（Phase 1G-B，ADR-039 独立实现）：
 * Type/MediaType/IsFolder → 统一 MediaType，协议差异止步于此。
 * 映射语义与 EmbyMediaItemMapper 对齐（同一领域模型），但**不共享代码**。
 */
object JellyfinItemMapper {

    /** Jellyfin RunTimeTicks 单位：100 纳秒；1 毫秒 = 10_000 ticks。 */
    private const val TICKS_PER_MILLIS = 10_000L

    /**
     * ProviderIds 字典 → [ExternalIds]。归一化**策略与 1E 冻结规则一致**
     * （ADR-037/039；本实现独立，禁止 import Emby mapper）：
     * key trim+lowercase、value trim、空白键值跳过；
     * **同一 provider 出现冲突值时整个 provider 丢弃**（不做 first/last wins）；
     * 只认 tmdb/imdb/tvdb，全部无效返回 null（unknown-only 等同无外部身份）。
     */
    internal fun mapProviderIds(raw: Map<String, String>?): ExternalIds? {
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
        val externalIds = ExternalIds(
            tmdb = normalized["tmdb"],
            imdb = normalized["imdb"],
            tvdb = normalized["tvdb"],
        )
        return externalIds.takeUnless { it.isEmpty }
    }

    /**
     * preflight（与 Emby 同一纪律）：Id 缺失/空白禁止进入领域层，返回 null 由调用方过滤。
     */
    fun map(dto: JellyfinItemDto, serverId: String): MediaItem? {
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
            studios = dto.studios.mapNotNull { it.name?.takeIf(String::isNotBlank) },
            year = dto.productionYear,
            runtimeMs = dto.runTimeTicks?.div(TICKS_PER_MILLIS),
            communityRating = dto.communityRating,
            officialRating = dto.officialRating,
            container = dto.container,
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
            people = dto.people.mapNotNull { mapPerson(it) },
            sortName = dto.sortName?.takeIf(String::isNotBlank) ?: title,
            externalIds = mapProviderIds(dto.providerIds),
        )
    }

    /** Person 图片 URL 由调用方 enrich（需要 server base，mapper 保持纯函数）。 */
    internal fun mapPerson(dto: JellyfinPersonDto): Person? {
        val name = dto.name?.takeIf(String::isNotBlank) ?: return null
        return Person(
            name = name,
            role = when (dto.type?.lowercase()) {
                "actor" -> Person.Role.ACTOR
                "director" -> Person.Role.DIRECTOR
                "writer" -> Person.Role.WRITER
                "producer" -> Person.Role.PRODUCER
                else -> Person.Role.OTHER
            },
            id = dto.id,
            type = dto.type,
            characterName = dto.role?.takeIf(String::isNotBlank)?.takeIf { dto.type?.lowercase() == "actor" },
        )
    }

    fun mapType(dto: JellyfinItemDto): MediaType = when (dto.type?.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.SERIES
        "season" -> MediaType.SEASON
        "episode" -> MediaType.EPISODE
        "audio" -> MediaType.AUDIO
        "video", "musicvideo" -> MediaType.VIDEO
        "folder", "collectionfolder", "userview" -> MediaType.FOLDER
        else -> {
            when {
                dto.isFolder -> MediaType.FOLDER
                dto.mediaType?.lowercase() == "video" -> MediaType.VIDEO
                dto.mediaType?.lowercase() == "audio" -> MediaType.AUDIO
                else -> MediaType.OTHER
            }
        }
    }
}
