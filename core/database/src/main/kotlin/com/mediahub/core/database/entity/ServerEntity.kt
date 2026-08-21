package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 媒体源（服务器/云盘/本地）配置表。敏感信息（Token 等）绝不在此存储。 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.mediahub.model.ServerType].name */
    val type: String,
    val username: String? = null,
    val note: String? = null,
    val icon: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val lastConnectedAtEpochMs: Long? = null,
    val lastError: String? = null,
)
