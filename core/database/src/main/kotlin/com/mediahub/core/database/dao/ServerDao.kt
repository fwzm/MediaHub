package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, createdAtEpochMs ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: String): ServerEntity?

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, createdAtEpochMs ASC LIMIT 1")
    suspend fun getFirst(): ServerEntity?

    @Upsert
    suspend fun upsert(entity: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE servers SET isDefault = 0")
    suspend fun clearDefaultFlag()

    @Query("UPDATE servers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: String)

    /** 原子「清旧设新默认」：单条 SQL，保证最多一个 isDefault==true（Server Editor 设默认 invariant）。 */
    @Query("UPDATE servers SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefaultExclusive(id: String)

    @Query("UPDATE servers SET lastConnectedAtEpochMs = :timestamp, lastError = NULL WHERE id = :id")
    suspend fun markConnected(id: String, timestamp: Long)

    @Query("UPDATE servers SET lastError = :error WHERE id = :id")
    suspend fun markError(id: String, error: String)
}
