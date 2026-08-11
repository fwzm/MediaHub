package com.mediahub.model

/** 媒体库（Emby/Jellyfin 的 Library；云盘/NAS 映射为顶层文件夹视图）。 */
data class MediaLibrary(
    val serverId: String,
    val id: String,
    val name: String,
    val type: LibraryType,
    val itemCount: Int? = null,
    val imageUrl: String? = null,
)
