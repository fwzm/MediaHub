package com.mediahub.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 媒体源线路表（Phase 1B-2.5）。一条 MediaServer 可有多条线路。 */
@Entity(tableName = "server_endpoints")
data class ServerEndpointEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val name: String,
    val url: String,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastTestedAtEpochMs: Long? = null,
    val lastApiLatencyMs: Long? = null,
    val lastMediaFirstByteMs: Long? = null,
    val lastMediaThroughputMbps: Double? = null,
    val lastProtocol: String? = null,
    val lastSupportsRange: Boolean? = null,
    val lastHttpCode: Int? = null,
)
