package com.mediahub.feature.settings.backup

import androidx.room.withTransaction
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.UserPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Full local persistent rows; read together in a Room transaction. Auth material is separate. */
data class BackupSnapshot(val servers: List<MediaServer>, val progress: List<PlaybackProgress>) {
    fun sameData(other: BackupSnapshot): Boolean =
        servers.map { it.copy(endpoints = it.endpoints.sortedBy { ep -> ep.id }) }.associateBy { it.id } ==
            other.servers.map { it.copy(endpoints = it.endpoints.sortedBy { ep -> ep.id }) }.associateBy { it.id } &&
            progress.associateBy { it.serverId to it.itemId } == other.progress.associateBy { it.serverId to it.itemId }
}

interface BackupDataSource {
    suspend fun readSnapshot(): BackupSnapshot
    /** Baseline validation and all Room changes share one transaction. */
    suspend fun applyRestorePlan(plan: RestorePlan)
}

data class RestorePlan(
    val planId: String,
    val record: BackupDtos.RestorePlanRecord,
    val servers: List<MediaServer>,
    val progress: List<PlaybackProgress>,
    val preferences: UserPreferences?,
    val expectedBaseline: BackupSnapshot? = null,
)

class RestoreBaselineChangedException : Exception("本机数据已在预览后变化，请重新预览")

/** Pure materialization shared by the frozen plan and Room adapter. */
internal fun materializeRestorePlan(before: BackupSnapshot, plan: RestorePlan): BackupSnapshot {
    if (plan.record.strategy == "ROLLBACK") return BackupSnapshot(plan.servers, plan.progress)
    val writing = plan.servers.filter { it.id in plan.record.overwriteServerIds && it.id !in plan.record.skipExistingServerIds }
    val merged = before.servers.associateBy { it.id }.toMutableMap().apply { writing.forEach { put(it.id, it) } }
    val defaultId = when (plan.record.strategy) {
        "MERGE" -> before.servers.firstOrNull { it.isDefault }?.id ?: writing.firstOrNull { it.isDefault }?.id
        else -> writing.firstOrNull { it.isDefault }?.id ?: merged.values.firstOrNull { it.isDefault }?.id
    }
    val servers = merged.values.map { it.copy(isDefault = it.id == defaultId) }
    val progress = before.progress.filterNot {
        plan.record.strategy == "REPLACE_SELECTED" && it.serverId in plan.record.overwriteServerIds
    }.associateBy { it.serverId to it.itemId }.toMutableMap().apply {
        plan.progress.forEach { incoming ->
            val key = incoming.serverId to incoming.itemId
            val local = get(key)
            if (plan.record.strategy != "MERGE" || local == null || local.updatedAtEpochMs < incoming.updatedAtEpochMs) put(key, incoming)
        }
    }.values.toList()
    return BackupSnapshot(servers, progress)
}

class ProductionBackupDataSource @Inject constructor(private val db: AppDatabase) : BackupDataSource {
    override suspend fun readSnapshot(): BackupSnapshot = db.withTransaction { readRows() }

    private suspend fun readRows(): BackupSnapshot {
        val endpoints = db.serverEndpointDao().observeAll().first().groupBy { it.serverId }
        return BackupSnapshot(
            db.serverDao().observeAll().first().map { it.toDomain(endpoints[it.id].orEmpty().map { ep -> ep.toDomain() }) },
            db.playbackProgressDao().getAll().map { p -> PlaybackProgress(
                serverId = p.serverId, itemId = p.itemId, positionMs = p.positionMs, durationMs = p.durationMs,
                isPaused = p.isPaused, updatedAtEpochMs = p.updatedAtEpochMs,
                mode = p.mode?.let(com.mediahub.model.PlaybackMode::valueOf), itemTitle = p.itemTitle, posterUrl = p.posterUrl,
                itemType = p.itemType?.let(com.mediahub.model.MediaType::valueOf),
            ) },
        )
    }

    override suspend fun applyRestorePlan(plan: RestorePlan) = db.withTransaction {
        val before = readRows()
        if (plan.expectedBaseline?.sameData(before) == false) throw RestoreBaselineChangedException()
        val canonicalPlan = plan.copy(servers = plan.servers.map { s -> s.copy(endpoints = s.endpoints.mapIndexed { index, ep ->
            if (ep.id.isBlank()) ep.copy(id = "${s.id}_ep$index") else ep
        }) })
        val after = materializeRestorePlan(before, canonicalPlan)
        val afterById = after.servers.associateBy { it.id }
        before.servers.filter { it.id !in afterById }.forEach {
            db.serverEndpointDao().deleteByServer(it.id)
            db.serverDao().deleteById(it.id)
        }
        val beforeById = before.servers.associateBy { it.id }
        after.servers.forEach { server ->
            val previous = beforeById[server.id]
            if (previous != server) {
                db.serverDao().upsert(server.toEntity())
                if (previous?.endpoints != server.endpoints) {
                    db.serverEndpointDao().deleteByServer(server.id)
                    db.serverEndpointDao().upsertAll(server.endpoints.map { it.toEntity() })
                }
            }
        }
        val progressByKey = after.progress.associateBy { it.serverId to it.itemId }
        before.progress.filter { (it.serverId to it.itemId) !in progressByKey }.forEach {
            db.playbackProgressDao().delete(it.serverId, it.itemId)
        }
        val oldProgress = before.progress.associateBy { it.serverId to it.itemId }
        db.playbackProgressDao().upsertAll(after.progress.filter { oldProgress[it.serverId to it.itemId] != it }.map { p ->
            PlaybackProgressEntity(p.serverId, p.itemId, p.positionMs, p.durationMs, p.isPaused, p.updatedAtEpochMs,
                p.mode?.name, p.itemTitle, p.posterUrl, p.itemType?.name)
        })
    }
}
