package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.TraktTrackedShowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TraktWatchlistDao {

    @Upsert
    suspend fun upsert(entity: TraktTrackedShowEntity)

    @Query("DELETE FROM trakt_tracked_shows WHERE traktId = :traktId")
    suspend fun delete(traktId: Int)

    @Query("SELECT * FROM trakt_tracked_shows ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<TraktTrackedShowEntity>>

    @Query("SELECT * FROM trakt_tracked_shows ORDER BY addedAtEpochMs DESC")
    suspend fun getAll(): List<TraktTrackedShowEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM trakt_tracked_shows WHERE traktId = :traktId)")
    suspend fun contains(traktId: Int): Boolean
}
