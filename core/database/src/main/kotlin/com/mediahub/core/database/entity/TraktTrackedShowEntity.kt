package com.mediahub.core.database.entity

import androidx.room.Entity

/**
 * 本地追更列表（条目 146）。
 *
 * 以 Trakt show id 为主键；只缓存追更状态与展示所需的最小元信息，
 * 日历与详情仍以 Trakt 实时返回为准。
 */
@Entity(tableName = "trakt_tracked_shows", primaryKeys = ["traktId"])
data class TraktTrackedShowEntity(
    val traktId: Int,
    val title: String,
    val year: Int?,
    val addedAtEpochMs: Long,
)
