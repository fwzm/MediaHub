package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 本地观影会话（条目 141）。
 *
 * 主键 = 会话 id（serverId + itemId + 开始时间派生），同一会话的周期 checkpoint
 * 走 Upsert 覆盖，不会产生重复记录。
 */
@Entity(
    tableName = "watch_sessions",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["startedAtEpochMs"]),
        Index(value = ["serverId", "itemId"]),
    ],
)
data class WatchSessionEntity(
    val id: String,
    val serverId: String,
    val itemId: String,
    val itemType: String,
    val title: String,
    val watchedMs: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val positionStartMs: Long,
    val positionEndMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)
