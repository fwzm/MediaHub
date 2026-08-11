package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账号信息表（非敏感字段）。 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("serverId")],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val userId: String? = null,
    val displayName: String,
    /** [com.mediahub.model.AuthState].name */
    val authState: String,
    val authenticatedAtEpochMs: Long? = null,
)
