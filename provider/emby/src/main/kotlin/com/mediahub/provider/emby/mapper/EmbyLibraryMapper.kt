package com.mediahub.provider.emby.mapper

import com.mediahub.model.LibraryType
import com.mediahub.model.MediaLibrary
import com.mediahub.provider.emby.api.EmbyBaseItemDto

/**
 * Emby View（顶层媒体库）→ 领域 MediaLibrary（Phase 1B-1）。
 * 协议差异（CollectionType）止步于此，UI 只依赖统一领域模型。
 */
object EmbyLibraryMapper {

    fun mapLibrary(dto: EmbyBaseItemDto, serverId: String): MediaLibrary? {
        val id = dto.id ?: return null
        val name = dto.name?.takeIf(String::isNotBlank) ?: id
        return MediaLibrary(
            serverId = serverId,
            id = id,
            name = name,
            type = mapLibraryType(dto.collectionType),
            imageUrl = null, // URL 由 EmbyLibraryProvider enrich（mapper 保持纯函数，见 EmbyImageMapper）
        )
    }

    private fun mapLibraryType(collectionType: String?): LibraryType = when (collectionType?.lowercase()) {
        "movies" -> LibraryType.MOVIES
        "tvshows" -> LibraryType.TV_SHOWS
        "music" -> LibraryType.MUSIC
        "musicvideos" -> LibraryType.MUSIC_VIDEOS
        "photos" -> LibraryType.PHOTOS
        "homevideos", "homes" -> LibraryType.HOMES
        "livetv" -> LibraryType.LIVE_TV
        else -> LibraryType.OTHER
    }
}
