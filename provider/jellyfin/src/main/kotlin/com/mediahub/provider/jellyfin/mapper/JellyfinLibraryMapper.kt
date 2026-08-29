package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.LibraryType
import com.mediahub.model.MediaLibrary
import com.mediahub.provider.jellyfin.api.JellyfinItemDto

/**
 * Jellyfin View/CollectionFolder → MediaLibrary（Phase 1G-B）。
 * CollectionType 协议差异止步于此，UI 只依赖统一领域模型。
 */
object JellyfinLibraryMapper {

    fun mapLibrary(dto: JellyfinItemDto, serverId: String): MediaLibrary? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
        val name = dto.name?.takeIf(String::isNotBlank) ?: return null
        return MediaLibrary(
            serverId = serverId,
            id = id,
            name = name,
            type = mapCollectionType(dto),
        )
    }

    private fun mapCollectionType(dto: JellyfinItemDto): LibraryType = when (dto.collectionType?.lowercase()) {
        "movies" -> LibraryType.MOVIES
        "tvshows" -> LibraryType.TV_SHOWS
        "music" -> LibraryType.MUSIC
        "musicvideos" -> LibraryType.MUSIC_VIDEOS
        "photos" -> LibraryType.PHOTOS
        "homevideos", "homes" -> LibraryType.HOMES
        "livetv" -> LibraryType.LIVE_TV
        "boxsets", "playlists", "folders" -> LibraryType.OTHER
        else -> LibraryType.OTHER
    }
}
