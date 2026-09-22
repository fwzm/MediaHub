package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.SubtitleMemoryEntity

@Dao
interface SubtitleMemoryDao {

    @Query("SELECT * FROM subtitle_memory WHERE versionKey = :versionKey")
    suspend fun get(versionKey: String): SubtitleMemoryEntity?

    @Upsert
    suspend fun upsert(entity: SubtitleMemoryEntity)

    @Query("DELETE FROM subtitle_memory WHERE versionKey = :versionKey")
    suspend fun delete(versionKey: String)

    @Query("DELETE FROM subtitle_memory WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("DELETE FROM subtitle_memory WHERE updatedAtEpochMs < :beforeEpochMs")
    suspend fun deleteOlderThan(beforeEpochMs: Long)
}
