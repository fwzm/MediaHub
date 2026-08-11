package com.mediahub.model

/**
 * 统一媒体类型。所有数据源（Emby/Jellyfin/NAS/云盘/本地）最终都映射为该枚举，
 * UI 层只依赖它，禁止出现 "if provider == X" 的分支。
 */
enum class MediaType {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
    VIDEO,
    AUDIO,
    FOLDER,
    LIVE_TV,
    OTHER;

    val isContainer: Boolean get() = this == FOLDER
    val isPlayable: Boolean
        get() = this == MOVIE || this == EPISODE || this == VIDEO || this == AUDIO || this == LIVE_TV
}
