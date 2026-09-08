package com.mediahub.feature.settings.backup

import androidx.room.withTransaction
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.dao.PlaybackProgressDao
import com.mediahub.core.database.dao.ServerDao
import com.mediahub.core.database.dao.ServerEndpointDao
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEndpointEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
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

    /**
     * 读取全量持久化快照（不限 continue watching 限量）。
     * 生产实现于单个 Room 事务内读取，保证服务器/线路/进度关联一致。
     */
    suspend fun readSnapshot(): BackupSnapshot

    /**
     * 应用恢复计划。生产实现把全部写入包在单个 Room 事务里：
     * 服务器/线路/进度要么全部生效，要么全部不生效（接口注释与实现一致）。
     */
    suspend fun applyRestorePlan(plan: RestorePlan)
}

/**
 * 恢复计划 = 载荷解析出的领域对象 + 冻结的决策记录（[BackupDtos.RestorePlanRecord]）。
 * 决策在生成时确定；中断恢复按同一记录重放，不凭内存重推。
 */
data class RestorePlan(
    val planId: String,
    val record: BackupDtos.RestorePlanRecord,
    val servers: List<MediaServer>,
    val progress: List<PlaybackProgress>,
    val preferences: UserPreferences?,
)

/** 生产实现（组合 Room DAO）。 */
class ProductionBackupDataSource @Inject constructor(
    private val db: AppDatabase,
) : BackupDataSource {

    private val serverDao: ServerDao get() = db.serverDao()
    private val endpointDao: ServerEndpointDao get() = db.serverEndpointDao()
    private val progressDao: PlaybackProgressDao get() = db.playbackProgressDao()

    override suspend fun readSnapshot(): BackupSnapshot = db.withTransaction {
        val serverEntities = serverDao.observeAll().first()
        val endpoints = endpointDao.observeAll().first().groupBy { it.serverId }
        val servers = serverEntities.map { entity ->
            MediaServer(
                id = entity.id, name = entity.name,
                type = runCatching { ServerType.valueOf(entity.type) }.getOrDefault(ServerType.LOCAL),
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
        BackupSnapshot(servers = servers, progress = progress)
    }

    override suspend fun applyRestorePlan(plan: RestorePlan) = db.withTransaction {
        for (server in plan.servers) {
            if (server.id in plan.record.skipExistingServerIds) continue
            if (server.id !in plan.record.overwriteServerIds) continue
            val serverEntity = ServerEntity(
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
                    ServerEndpointEntity(
                        id = if (ep.id.isBlank()) "${server.id}_ep$index" else ep.id,
                        serverId = server.id,
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                )
            }
        }
        progressDao.upsertAll(plan.progress.map { p ->
            PlaybackProgressEntity(
                serverId = p.serverId, itemId = p.itemId,
                positionMs = p.positionMs, durationMs = p.durationMs,
                isPaused = p.isPaused, updatedAtEpochMs = p.updatedAtEpochMs,
                mode = p.mode?.name, itemTitle = p.itemTitle,
                posterUrl = p.posterUrl, itemType = p.itemType?.name,
            )
        })
    }
}
