package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.HiddenContentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenContentDao {

    @Upsert
    suspend fun upsert(entity: HiddenContentEntity)

    @Query("DELETE FROM hidden_content WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)

    @Query("SELECT * FROM hidden_content")
    fun observeAll(): Flow<List<HiddenContentEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_content WHERE serverId = :serverId AND itemId = :itemId)")
    suspend fun contains(serverId: String, itemId: String): Boolean
}
