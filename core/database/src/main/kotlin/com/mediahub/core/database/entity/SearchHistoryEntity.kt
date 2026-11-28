package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 本地搜索历史（条目 52/120）。
 *
 * 主键 [query] 保证同一关键词只保留一条（去重）；重复搜索只刷新 [updatedAtEpochMs]，
 * 由仓库按时间倒序读取。表上限由 `SearchHistoryRepository.MAX_ENTRIES` 裁剪。
 */
@Entity(
    tableName = "search_history",
    primaryKeys = ["query"],
    indices = [Index("updatedAtEpochMs")],
)
data class SearchHistoryEntity(
    val query: String,
    val updatedAtEpochMs: Long,
)
