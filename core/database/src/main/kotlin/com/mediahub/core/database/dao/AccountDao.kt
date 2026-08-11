package com.mediahub.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mediahub.core.database.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE serverId = :serverId")
    fun observeForServer(serverId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE serverId = :serverId LIMIT 1")
    suspend fun getForServer(serverId: String): AccountEntity?

    @Upsert
    suspend fun upsert(entity: AccountEntity)

    @Query("DELETE FROM accounts WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)
}
