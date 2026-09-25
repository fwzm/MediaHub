package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 字幕匹配记忆（P2 字幕中心切片一）。
 *
 * 主键 [versionKey] = 视频版本指纹（[com.mediahub.core.database.repository.SubtitleMemoryKeys]）:
 * serverId + itemId + sizeBytes（无 size 时回退路径指纹）。**不同版本互不串用**，
 * 偏移因此天然不跨视频共享。serverId 列用于删除媒体源时级联清理。
 */
@Entity(
    tableName = "subtitle_memory",
    primaryKeys = ["versionKey"],
    indices = [Index("serverId")],
)
data class SubtitleMemoryEntity(
    val versionKey: String,
    val serverId: String,
    /** 选中的字幕稳定标识（URL/本地路径/SAF uri）；null = 只记偏移。 */
    val subtitleId: String? = null,
    /** 字幕时间轴偏移（ms，正 = 延后）。 */
    val offsetMs: Long = 0,
    val updatedAtEpochMs: Long,
)
