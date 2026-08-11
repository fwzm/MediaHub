package com.mediahub.model

/**
 * 播放进度。本地持久化一份快照用于"继续观看"，
 * 并异步回传给对应 Provider（Emby/Jellyfin 的 Reporting API）。
 */
data class PlaybackProgress(
    val serverId: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
    val updatedAtEpochMs: Long,
    val sessionId: String? = null,
    val mode: PlaybackMode? = null,
    /** 展示用快照（本地"继续观看"列表需要，不视为权威数据） */
    val itemTitle: String? = null,
    val posterUrl: String? = null,
    val itemType: MediaType? = null,
)
