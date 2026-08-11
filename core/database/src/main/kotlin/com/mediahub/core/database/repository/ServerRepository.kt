package com.mediahub.core.database.repository

import com.mediahub.core.common.IdGenerator
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 媒体源（服务器）的本地仓库。 */
@Singleton
class ServerRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val dao = db.serverDao()

    fun observeServers(): Flow<List<MediaServer>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getServer(id: String): MediaServer? = dao.getById(id)?.toDomain()

    /** 新增媒体源；首条自动设为默认。 */
    suspend fun addServer(server: MediaServer): MediaServer {
        val count = dao.count()
        val entity = server.toEntity().copy(
            id = server.id.ifBlank { IdGenerator.newId("srv") },
            sortOrder = count,
            isDefault = if (count == 0) true else server.isDefault,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun updateServer(server: MediaServer) {
        dao.upsert(server.toEntity())
    }

    suspend fun deleteServer(id: String) {
        dao.deleteById(id)
    }

    suspend fun setDefault(id: String) {
        dao.clearDefaultFlag()
        dao.setDefault(id)
    }

    suspend fun markConnected(id: String, timestampEpochMs: Long = System.currentTimeMillis()) {
        dao.markConnected(id, timestampEpochMs)
    }

    suspend fun markError(id: String, error: String) {
        dao.markError(id, error)
    }
}
