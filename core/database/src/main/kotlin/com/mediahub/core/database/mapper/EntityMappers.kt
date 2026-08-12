package com.mediahub.core.database.mapper

import com.mediahub.core.database.entity.AccountEntity
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.model.AuthState
import com.mediahub.model.MediaAccount
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress

/** Entity ↔ Domain 映射（数据库层与领域层解耦）。 */
object ServerEntityMappers {

    fun ServerEntity.toDomain(): MediaServer = MediaServer(
        id = id,
        name = name,
        providerId = legacyTypeToProviderId(type),
        baseUrl = baseUrl,
        username = username,
        isDefault = isDefault,
        sortOrder = sortOrder,
        createdAtEpochMs = createdAtEpochMs,
        lastConnectedAtEpochMs = lastConnectedAtEpochMs,
        lastError = lastError,
    )

    fun MediaServer.toEntity(): ServerEntity = ServerEntity(
        id = id,
        name = name,
        type = providerId,
        baseUrl = baseUrl,
        username = username,
        isDefault = isDefault,
        sortOrder = sortOrder,
        createdAtEpochMs = createdAtEpochMs,
        lastConnectedAtEpochMs = lastConnectedAtEpochMs,
        lastError = lastError,
    )

    fun AccountEntity.toDomain(): MediaAccount = MediaAccount(
        id = id,
        serverId = serverId,
        userId = userId,
        displayName = displayName,
        authState = runCatching { AuthState.valueOf(authState) }.getOrDefault(AuthState.NONE),
        authenticatedAtEpochMs = authenticatedAtEpochMs,
    )

    fun MediaAccount.toEntity(): AccountEntity = AccountEntity(
        id = id,
        serverId = serverId,
        userId = userId,
        displayName = displayName,
        authState = authState.name,
        authenticatedAtEpochMs = authenticatedAtEpochMs,
    )

    fun PlaybackProgressEntity.toDomain(): PlaybackProgress = PlaybackProgress(
        serverId = serverId,
        itemId = itemId,
        positionMs = positionMs,
        durationMs = durationMs,
        isPaused = isPaused,
        updatedAtEpochMs = updatedAtEpochMs,
        mode = mode?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() },
        itemTitle = itemTitle,
        posterUrl = posterUrl,
        itemType = itemType?.let { runCatching { MediaType.valueOf(it) }.getOrNull() },
    )

    fun PlaybackProgress.toEntity(): PlaybackProgressEntity = PlaybackProgressEntity(
        serverId = serverId,
        itemId = itemId,
        positionMs = positionMs,
        durationMs = durationMs,
        isPaused = isPaused,
        updatedAtEpochMs = updatedAtEpochMs,
        mode = mode?.name,
        itemTitle = itemTitle,
        posterUrl = posterUrl,
        itemType = itemType?.name,
    )

    /** Phase 0 枚举名 → Phase 0.5 稳定 providerId；未知字符串原样保留。 */
    internal fun legacyTypeToProviderId(value: String): String = when (value) {
        "EMBY" -> "emby"
        "JELLYFIN" -> "jellyfin"
        "PLEX" -> "plex"
        "FN_NAS" -> "fnnas"
        "WEBDAV" -> "webdav"
        "SMB" -> "smb"
        "LOCAL" -> "local"
        "ALIYUN_DRIVE" -> "aliyundrive"
        "BAIDU_DRIVE" -> "baidudrive"
        "QUARK_DRIVE" -> "quarkdrive"
        "CHINA_MOBILE_CLOUD" -> "chinamobilecloud"
        "TIANYI_CLOUD" -> "tianyicloud"
        else -> value.lowercase()
    }
}
