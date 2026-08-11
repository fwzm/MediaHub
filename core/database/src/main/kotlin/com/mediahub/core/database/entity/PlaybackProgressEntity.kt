package com.mediahub.core.database.entity

import androidx.room.Entity

/** 本地播放进度缓存（"继续观看"数据源）。权威进度以各 Provider 服务端为准。 */
@Entity(tableName = "playback_progress", primaryKeys = ["serverId", "itemId"])
data class PlaybackProgressEntity(
    val serverId: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
    val updatedAtEpochMs: Long,
    /** [com.mediahub.model.PlaybackMode].name */
    val mode: String? = null,
    val itemTitle: String? = null,
    val posterUrl: String? = null,
    /** [com.mediahub.model.MediaType].name */
    val itemType: String? = null,
)
