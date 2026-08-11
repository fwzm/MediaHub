package com.mediahub.model

/** 媒体库类型（对应 Emby/Jellyfin 的 CollectionType，云盘/NAS 映射为 FILES）。 */
enum class LibraryType {
    MOVIES,
    TV_SHOWS,
    MUSIC,
    MUSIC_VIDEOS,
    PHOTOS,
    HOMES,
    LIVE_TV,
    FILES,
    OTHER
}
