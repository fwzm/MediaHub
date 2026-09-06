package com.mediahub.feature.settings.backup

import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.database.dao.PlaybackProgressDao
import com.mediahub.core.database.dao.ServerDao
import com.mediahub.core.database.dao.ServerEndpointDao
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * 完整数据库快照（备份专用，与播放器消费的 Store 接口解耦）。
 */
data class BackupSnapshot(
    val servers: List<MediaServer>,
    val progress: List<PlaybackProgress>,
)

/**
 * 备份专用数据适配接口（Phase 1I-A review：不扩大播放器 Store 接口）。
 * 生产实现组合 DAO + 偏好；测试用独立 fake。
 */
interface BackupDataSource {
    /** 读取全量持久化快照（不限 continue watching 限量）。 */
    suspend fun readSnapshot(): BackupSnapshot

    /** 应用恢复计划（事务内批量 upsert）。 */
    suspend fun applyRestorePlan(plan: RestorePlan)
}

/** 恢复计划（预览→确认→应用的三阶段中，此对象绑定确认时的数据版本）。 */
data class RestorePlan(
    val servers: List<MediaServer>,
    val progress: List<PlaybackProgress>,
    val skipExistingServerIds: Set<String>,
)

/** 生产实现（组合 Room DAO）。 */
class ProductionBackupDataSource @Inject constructor(
    private val serverDao: ServerDao,
    private val endpointDao: ServerEndpointDao,
    private val progressDao: PlaybackProgressDao,
) : BackupDataSource {

    override suspend fun readSnapshot(): BackupSnapshot {
        val serverEntities = serverDao.observeAll().first()
        val endpoints = endpointDao.observeAll().first().groupBy { it.serverId }
        val servers = serverEntities.map { entity ->
            MediaServer(
                id = entity.id, name = entity.name,
                type = ServerType.valueOf(entity.type),
                username = entity.username, note = entity.note,
                isDefault = entity.isDefault, sortOrder = entity.sortOrder,
                createdAtEpochMs = entity.createdAtEpochMs,
                endpoints = endpoints[entity.id].orEmpty().map { ep ->
                    ServerEndpoint(
                        id = ep.id, serverId = ep.serverId,
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                },
            )
        }
        val progress = progressDao.getAll().map { entity ->
            PlaybackProgress(
                serverId = entity.serverId, itemId = entity.itemId,
                positionMs = entity.positionMs, durationMs = entity.durationMs,
                isPaused = entity.isPaused, updatedAtEpochMs = entity.updatedAtEpochMs,
                mode = entity.mode?.let { runCatching { com.mediahub.model.PlaybackMode.valueOf(it) }.getOrNull() },
                itemTitle = entity.itemTitle, posterUrl = entity.posterUrl,
                itemType = entity.itemType?.let { runCatching { com.mediahub.model.MediaType.valueOf(it) }.getOrNull() },
            )
        }
        return BackupSnapshot(servers = servers, progress = progress)
    }

    override suspend fun applyRestorePlan(plan: RestorePlan) {
        for (server in plan.servers) {
            if (server.id in plan.skipExistingServerIds) continue
            val serverEntity = com.mediahub.core.database.entity.ServerEntity(
                id = server.id, name = server.name,
                type = server.type.name,
                username = server.username, note = server.note,
                isDefault = server.isDefault, sortOrder = server.sortOrder,
                createdAtEpochMs = server.createdAtEpochMs,
            )
            serverDao.upsert(serverEntity)
            endpointDao.deleteByServer(server.id)
            server.endpoints.forEachIndexed { index, ep ->
                endpointDao.upsert(
                    com.mediahub.core.database.entity.ServerEndpointEntity(
                        id = if (ep.id.isBlank()) "${server.id}_ep$index" else ep.id,
                        serverId = server.id,
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                )
            }
        }
        progressDao.upsertAll(plan.progress.map { p ->
            com.mediahub.core.database.entity.PlaybackProgressEntity(
                serverId = p.serverId, itemId = p.itemId,
                positionMs = p.positionMs, durationMs = p.durationMs,
                isPaused = p.isPaused, updatedAtEpochMs = p.updatedAtEpochMs,
                mode = p.mode?.name, itemTitle = p.itemTitle,
                posterUrl = p.posterUrl, itemType = p.itemType?.name,
            )
        })
    }
}
