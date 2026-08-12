package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 媒体源（服务器/云盘/本地）配置表。敏感信息（Token 等）绝不在此存储。 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /**
     * 历史列名仍为 type，值语义已迁移为稳定 providerId。
     * 旧版枚举值由 Mapper 读取时转换，避免 Phase 0.5 无必要重建 Room 表。
     */
    val type: String,
    val baseUrl: String,
    val username: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val lastConnectedAtEpochMs: Long? = null,
    val lastError: String? = null,
)
