package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.ServerEndpointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerEndpointDao {

    @Query("SELECT * FROM server_endpoints ORDER BY serverId ASC, sortOrder ASC")
    fun observeAll(): Flow<List<ServerEndpointEntity>>

    @Query("SELECT * FROM server_endpoints WHERE serverId = :serverId ORDER BY sortOrder ASC")
    suspend fun getByServer(serverId: String): List<ServerEndpointEntity>

    @Upsert
    suspend fun upsert(entity: ServerEndpointEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ServerEndpointEntity>)

    @Query("DELETE FROM server_endpoints WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}
