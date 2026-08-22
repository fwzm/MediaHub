package com.mediahub.model

/**
 * 播放启动快照（Phase Player Startup 优化）。
 * 详情页已拿到完整 [MediaItem] 时，把播放所需的最小字段随导航传给播放页，
 * 避免播放页重复 GET detail。
 */
data class PlaybackLaunchSnapshot(
    val itemId: String,
    val type: MediaType? = null,
    val title: String = "",
    val runtimeMs: Long? = null,
    val posterUrl: String? = null,
    val container: String? = null,
) {
    /** 详情页已提供有效快照（type 非空）时，播放页可跳过 detail 拉取。 */
    val hasDetail: Boolean get() = type != null
}
