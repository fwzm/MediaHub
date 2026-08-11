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
import com.mediahub.model.ServerType

/** Entity ↔ Domain 映射（数据库层与领域层解耦）。 */
object ServerEntityMappers {

    fun ServerEntity.toDomain(): MediaServer = MediaServer(
        id = id,
        name = name,
        type = runCatching { ServerType.valueOf(type) }.getOrElse { ServerType.LOCAL },
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
        type = type.name,
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
}
