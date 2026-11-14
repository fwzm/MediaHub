package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("SELECT query FROM search_history ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<String>>

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    /** 只保留最新 [keep] 条，其余（含同时间戳的旧行）一并删除。 */
    @Query(
        "DELETE FROM search_history WHERE query NOT IN " +
            "(SELECT query FROM search_history ORDER BY updatedAtEpochMs DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
