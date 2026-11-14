package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.WatchSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchStatsDao {

    @Upsert
    suspend fun upsert(entity: WatchSessionEntity)

    @Query("SELECT * FROM watch_sessions ORDER BY startedAtEpochMs ASC")
    fun observeAll(): Flow<List<WatchSessionEntity>>

    @Query("SELECT * FROM watch_sessions ORDER BY startedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchSessionEntity>

    @Query("DELETE FROM watch_sessions")
    suspend fun deleteAll()
}
