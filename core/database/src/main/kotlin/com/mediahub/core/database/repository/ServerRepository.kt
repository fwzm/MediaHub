package com.mediahub.core.database.repository

import com.mediahub.core.common.IdGenerator
import com.mediahub.core.common.ServerAddressIdentity
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.core.database.sync.ServerSyncMappers.toLocalSourceRecord
import com.mediahub.core.database.sync.ServerSyncMappers.toSyncIdentity
import com.mediahub.model.MediaServer
import com.mediahub.model.activeEndpoint
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 媒体源（服务器）的本地仓库。 */
@Singleton
class ServerRepository @Inject constructor(
    private val db: AppDatabase,
) : ServerStore, SyncSourceStore {
    private val dao = db.serverDao()
    private val endpointDao = db.serverEndpointDao()

    override fun observeServers(): Flow<List<MediaServer>> =
        combine(dao.observeAll(), endpointDao.observeAll()) { servers, endpoints ->
            val byServer = endpoints.groupBy { it.serverId }
            servers.map { entity ->
                entity.toDomain(byServer[entity.id].orEmpty().map { it.toDomain() })
            }
        }

    override suspend fun getServer(id: String): MediaServer? {
        val entity = dao.getById(id) ?: return null
        val endpoints = endpointDao.getByServer(id).map { it.toDomain() }
        return entity.toDomain(endpoints)
    }

    // ---- 跨设备来源同步（W6-B）：读取在 SyncSourceStore，写入只在下面三个方法 ----

    override suspend fun listLocalSources(): List<LocalSourceRecord> {
        val servers = dao.getAll()
        if (servers.isEmpty()) return emptyList()
        val endpointsByServer = endpointDao.getAll().map { it.toDomain() }.groupBy { it.serverId }
        return servers.map { entity ->
            entity.toLocalSourceRecord(endpointsByServer[entity.id].orEmpty().activeEndpoint()?.url)
        }
    }

    override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? {
        val entity = dao.getBySyncSourceId(syncSourceId) ?: return null
        val endpoints = endpointDao.getByServer(entity.id).map { it.toDomain() }
        return entity.toLocalSourceRecord(endpoints.activeEndpoint()?.url)
    }

    /**
     * 绑定同步身份三列（首次配对/导入落库时用）。返回 false 表示服务器不存在。
     *
     * sync_source_id 上有唯一索引：把同一同步身份绑到第二条来源会抛 SQLiteConstraintException，
     * 由调用方先做冲突判定（预演阶段已经会给出 CONFLICT），不要在这里静默覆盖。
     */
    suspend fun bindSyncSourceId(
        serverId: String,
        syncSourceId: String,
        recordRevision: Long = 0L,
        sourceTypeRaw: String? = null,
    ): Boolean = dao.updateSyncIdentity(serverId, syncSourceId, recordRevision, sourceTypeRaw) > 0

    /** 写回记录修订号（合并次序的唯一依据）。返回 false 表示服务器不存在。 */
    suspend fun updateSyncRecordRevision(serverId: String, revision: Long): Boolean =
        dao.updateSyncRecordRevision(serverId, revision) > 0

    /** 按规划视图写回身份三列；只碰同步字段，绝不改 name/type/username。 */
    suspend fun applySyncIdentity(serverId: String, record: LocalSourceRecord): Boolean {
        val identity = record.toSyncIdentity()
        return dao.updateSyncIdentity(serverId, identity.syncSourceId, identity.recordRevision, identity.sourceTypeRaw) > 0
    }

    /** 新增媒体源；首条自动设为默认。线路按传入写入（为空则无线路）。 */
    suspend fun addServer(server: MediaServer): MediaServer {
        val count = dao.count()
        val serverId = server.id.ifBlank { IdGenerator.newId("srv") }
        val entity = server.toEntity().copy(
            id = serverId,
            sortOrder = count,
            isDefault = if (count == 0) true else server.isDefault,
        )
        dao.upsert(entity)
        val endpoints = server.endpoints.mapIndexed { index, ep ->
            ep.copy(
                id = ep.id.ifBlank { serverId + "_ep" + index },
                serverId = serverId,
                isPrimary = if (index == 0) true else ep.isPrimary,
            ).toEntity()
        }
        if (endpoints.isNotEmpty()) endpointDao.upsertAll(endpoints)
        return entity.toDomain(server.endpoints.mapIndexed { index, ep ->
            ep.copy(id = ep.id.ifBlank { serverId + "_ep" + index }, serverId = serverId)
        })
    }

    /** 更新媒体源；身份替换与旧字幕记忆失效必须和线路写入一起提交。 */
    override suspend fun updateServer(server: MediaServer) = db.withTransaction {
        val previous = getServer(server.id)
        if (previous == null || subtitleSourceIdentity(previous) != subtitleSourceIdentity(server)) {
            db.subtitleMemoryDao().deleteByServer(server.id)
        }
        // 同步身份不在 MediaServer 上（领域模型不带协议字段），所以 toEntity() 会把三列重置成默认值；
        // 编辑名称/线路时必须原样保留，否则一次「改名」就抹掉来源的同步身份，下次同步会把它当成全新来源。
        val previousEntity = dao.getById(server.id)
        val entity = server.toEntity().let { updated ->
            if (previousEntity == null) {
                updated
            } else {
                updated.copy(
                    syncSourceId = previousEntity.syncSourceId,
                    syncRecordRevision = previousEntity.syncRecordRevision,
                    syncSourceTypeRaw = previousEntity.syncSourceTypeRaw,
                )
            }
        }
        dao.upsert(entity)
        endpointDao.deleteByServer(server.id)
        val endpoints = server.endpoints.mapIndexed { index, ep ->
            ep.copy(
                id = ep.id.ifBlank { server.id + "_ep" + index },
                serverId = server.id,
            ).toEntity()
        }
        if (endpoints.isNotEmpty()) endpointDao.upsertAll(endpoints)
    }

    private fun subtitleSourceIdentity(server: MediaServer) = Triple(
        server.type,
        ServerAddressIdentity.normalize(server.baseUrl),
        server.username?.trim().orEmpty(),
    )

    suspend fun deleteServer(id: String) {
        val wasDefault = dao.getById(id)?.isDefault == true
        dao.deleteById(id)
        endpointDao.deleteByServer(id)
        // 删除的是默认媒体源时，重选首条为默认（保持最多一个 default 不变式）
        if (wasDefault) {
            dao.getFirst()?.let { dao.setDefaultExclusive(it.id) }
        }
    }

    /** 设为默认：原子「清旧设新」（单条 SQL），保证最多一个 isDefault==true。 */
    override suspend fun setDefault(id: String) {
        dao.setDefaultExclusive(id)
    }

    suspend fun markConnected(id: String, timestampEpochMs: Long = System.currentTimeMillis()) {
        dao.markConnected(id, timestampEpochMs)
    }

    suspend fun markError(id: String, error: String) {
        dao.markError(id, error)
    }

    /**
     * 线路质量测试结果落库（U4-D）。Phase 1I review P2：
     * 校验与写入在同一事务内——[endpointId] 对应线路当前 URL 仍等于 [expectedUrl]
     * 才写入（防"检查-写入"竞态）；线路已被改指其他地址或不存在则跳过，
     * 绝不重挑主线路承接旧测量。
     */
    override suspend fun updateEndpointQuality(
        serverId: String,
        endpointId: String,
        expectedUrl: String,
        apiLatencyMs: Long?,
        mediaFirstByteMs: Long?,
        throughputMbps: Double?,
        protocol: String?,
        supportsRange: Boolean?,
        httpCode: Int?,
    ) = db.withTransaction {
        val target = endpointDao.getByServer(serverId).firstOrNull { it.id == endpointId } ?: return@withTransaction
        if (target.url != expectedUrl) return@withTransaction
        endpointDao.upsert(target.copy(
            lastApiLatencyMs = apiLatencyMs,
            lastMediaFirstByteMs = mediaFirstByteMs,
            lastMediaThroughputMbps = throughputMbps,
            lastProtocol = protocol,
            lastSupportsRange = supportsRange,
            lastHttpCode = httpCode,
            lastTestedAtEpochMs = System.currentTimeMillis(),
        ))
    }
}
