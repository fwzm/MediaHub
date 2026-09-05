package com.mediahub.core.database.repository

import com.mediahub.core.common.IdGenerator
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 媒体源（服务器）的本地仓库。 */
@Singleton
class ServerRepository @Inject constructor(
    private val db: AppDatabase,
) : ServerStore {
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

    /** 更新媒体源（整体替换线路）。 */
    override suspend fun updateServer(server: MediaServer) {
        dao.upsert(server.toEntity())
        endpointDao.deleteByServer(server.id)
        val endpoints = server.endpoints.mapIndexed { index, ep ->
            ep.copy(
                id = ep.id.ifBlank { server.id + "_ep" + index },
                serverId = server.id,
            ).toEntity()
        }
        if (endpoints.isNotEmpty()) endpointDao.upsertAll(endpoints)
    }

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

    /** 线路质量测试结果落库（U4-D）。 */
    override suspend fun updateEndpointQuality(
        serverId: String,
        apiLatencyMs: Long?,
        mediaFirstByteMs: Long?,
        throughputMbps: Double?,
        protocol: String?,
        supportsRange: Boolean?,
        httpCode: Int?,
    ) {
        val endpoints = endpointDao.getByServer(serverId)
        val primary = endpoints.firstOrNull { it.isPrimary } ?: endpoints.firstOrNull() ?: return
        endpointDao.upsert(primary.copy(
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
