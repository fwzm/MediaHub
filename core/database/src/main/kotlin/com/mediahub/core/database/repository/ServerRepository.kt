package com.mediahub.core.database.repository

import com.mediahub.core.common.IdGenerator
import com.mediahub.core.common.ServerAddressIdentity
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceEndpoint as ContractEndpoint
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.core.database.sync.ServerSyncMappers.sourceType
import com.mediahub.core.database.sync.ServerSyncMappers.toLocalSourceRecord
import com.mediahub.core.database.sync.ServerSyncMappers.toSyncIdentity
import com.mediahub.core.database.sync.SourceSyncSourceSnapshot
import com.mediahub.core.database.sync.SourceSyncStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
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
) : ServerStore, SyncSourceStore, SourceSyncStore {
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

    // ---- 跨设备来源同步（W6-C）：可用路径的存储侧实现 ----
    //
    // 这一层只负责「信封里的来源」与「本机 servers 行」之间的搬运：
    // 领域模型 MediaServer 不带同步身份（同步三列是协议字段，不该扩散到所有 ViewModel），
    // 所以同步身份的读写一律走 DAO 的单列更新，绝不触发 toEntity() 的全量覆盖。

    override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> {
        val servers = dao.getAll()
        if (servers.isEmpty()) return emptyList()
        val endpointsByServer = endpointDao.getAll().groupBy { it.serverId }
        return servers.map { entity ->
            val endpoints = endpointsByServer[entity.id].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .filter { it.url.isNotBlank() }
                .map { ep ->
                    ContractEndpoint(
                        url = ep.url,
                        isPrimary = ep.isPrimary,
                        enabled = ep.enabled,
                        sortOrder = ep.sortOrder,
                    )
                }
            SourceSyncSourceSnapshot(
                serverId = entity.id,
                name = entity.name,
                serverType = entity.sourceType(),
                sourceTypeRaw = entity.syncSourceTypeRaw,
                accountScope = entity.username?.trim()?.takeIf { it.isNotEmpty() },
                syncSourceId = entity.syncSourceId,
                recordRevision = entity.syncRecordRevision,
                endpoints = endpoints,
                createdAtEpochMs = entity.createdAtEpochMs,
            )
        }
    }

    /**
     * 新建一条本机来源并立刻写上同步身份。
     *
     * 首条来源会被 [addServer] 自动设为默认、index 0 线路强制为主线路——
     * 因此先把端点按「主线路优先、再按 sortOrder」排好，免得把副线路扶正。
     *
     * @throws IllegalArgumentException 信封来源没有已知类型或没有 syncSourceId（调用方应已拦截）。
     */
    override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
        val type = requireNotNull(snapshot.serverType) {
            "创建来源必须有已知类型：serverType 为 null，serverId=" + snapshot.serverId
        }
        val syncSourceId = requireNotNull(snapshot.syncSourceId?.takeIf { it.isNotBlank() }) {
            "创建来源必须带 syncSourceId，serverId=" + snapshot.serverId
        }
        return db.withTransaction {
            val created = addServer(
                MediaServer(
                    id = IdGenerator.newId("srv"),
                    name = snapshot.name.ifBlank { type.label },
                    type = type,
                    username = snapshot.accountScope,
                    createdAtEpochMs = snapshot.createdAtEpochMs,
                    endpoints = snapshot.endpoints.toModelEndpoints(emptyList()),
                ),
            )
            dao.updateSyncIdentity(created.id, syncSourceId, snapshot.recordRevision, snapshot.sourceTypeRaw)
            created.id
        }
    }

    /**
     * 用信封里的来源数据更新本机一条已有来源（名称 / 类型 / 账号 / 线路），再写回身份三列。
     *
     * 返回 false 表示：本机没有这条来源，或信封记录缺类型 / 缺 syncSourceId。
     * 线路沿用 [updateServer] 的「整组替换」语义（旧线路删除后重建），因此 id 会重新生成。
     */
    override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
        val existing = getServer(serverId) ?: return false
        val type = snapshot.serverType ?: return false
        val syncSourceId = snapshot.syncSourceId?.takeIf { it.isNotBlank() } ?: return false
        val merged = existing.copy(
            name = snapshot.name.ifBlank { existing.name },
            type = type,
            username = snapshot.accountScope,
            endpoints = snapshot.endpoints.toModelEndpoints(existing.endpoints),
        )
        db.withTransaction {
            updateServer(merged)
            dao.updateSyncIdentity(serverId, syncSourceId, snapshot.recordRevision, snapshot.sourceTypeRaw)
        }
        return true
    }

    override suspend fun bindSyncIdentity(
        serverId: String,
        syncSourceId: String,
        recordRevision: Long,
        sourceTypeRaw: String?,
    ): Boolean = dao.updateSyncIdentity(serverId, syncSourceId, recordRevision, sourceTypeRaw) > 0

    /**
     * 契约端点 → 领域线路。已有同序线路时复用其 id（避免每次同步都换线路身份、
     * 把质量测量历史挂在旧 id 上变成孤儿），否则交给 [addServer]/[updateServer] 生成。
     */
    private fun List<ContractEndpoint>.toModelEndpoints(existing: List<ServerEndpoint>): List<ServerEndpoint> {
        val ordered = sortedWith(
            compareByDescending<ContractEndpoint> { it.isPrimary }.thenBy { it.sortOrder },
        )
        return ordered.mapIndexed { index, ep ->
            ServerEndpoint(
                id = existing.getOrNull(index)?.id.orEmpty(),
                serverId = existing.getOrNull(index)?.serverId.orEmpty(),
                name = "线路 " + (index + 1),
                url = ep.url,
                isPrimary = ep.isPrimary,
                enabled = ep.enabled,
                sortOrder = ep.sortOrder,
            )
        }
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
