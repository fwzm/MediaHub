package com.mediahub.core.database.entity

import androidx.room.Entity

/**
 * 隐藏空间内容标记（条目 139）。
 *
 * 统计侧只读取该表做**无条件过滤**：条目一旦被标记为隐藏，其历史会话不再出现在
 * 统计/日历/历史/回顾中；隐藏空间锁定与否都不影响该过滤（避免"锁定后仍显示记录"）。
 * 标记本身由隐藏空间模块写入，本仓库当前没有该 UI，此处只提供存储与查询契约。
 */
@Entity(tableName = "hidden_content", primaryKeys = ["serverId", "itemId"])
data class HiddenContentEntity(
    val serverId: String,
    val itemId: String,
    val hiddenAtEpochMs: Long,
)
