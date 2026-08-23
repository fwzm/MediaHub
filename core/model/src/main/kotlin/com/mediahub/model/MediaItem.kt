package com.mediahub.model

/**
 * 统一媒体条目。无论来自 Emby、Jellyfin、NAS、WebDAV 还是云盘，
 * 最终都以该模型呈现给 UI。Provider 负责把各自的 Remote Model 映射到这里。
 */
data class MediaItem(
    val serverId: String,
    val id: String,
    val type: MediaType,
    val title: String,
    val libraryId: String? = null,
    /** 父目录 / 上级条目 id（用于云盘、NAS 的文件树导航） */
    val parentId: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val overview: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val people: List<Person> = emptyList(),
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val runtimeMs: Long? = null,
    val communityRating: Double? = null,
    /** 文件容器，如 mkv / mp4 / ts（用于播放兼容性评估） */
    val container: String? = null,
    /** 文件大小（字节），云盘 / NAS 场景有意义 */
    val sizeBytes: Long? = null,
    /** 云端 / NAS 上的相对路径（资源标识，播放时再解析，见 ADR-003） */
    val path: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val userData: UserData? = null,
    val sortName: String = title,
)

/** 服务端保存的用户数据（播放进度等）。 */
data class UserData(
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val playedPercentage: Double? = null,
    val playbackPositionMs: Long? = null,
)
