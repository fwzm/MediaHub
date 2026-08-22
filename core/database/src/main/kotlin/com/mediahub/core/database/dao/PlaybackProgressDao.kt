package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {

    @Upsert
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun get(serverId: String, itemId: String): PlaybackProgressEntity?

    @Query("DELETE FROM playback_progress WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)

    @Query("DELETE FROM playback_progress WHERE updatedAtEpochMs < :beforeEpochMs")
    suspend fun deleteOlderThan(beforeEpochMs: Long)

    @Query("DELETE FROM playback_progress WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}
