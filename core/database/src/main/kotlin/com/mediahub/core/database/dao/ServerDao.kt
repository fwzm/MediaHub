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

    /** 全量快照（同步预演一次性读取，避免 N+1 查询）。 */
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, createdAtEpochMs ASC")
    suspend fun getAll(): List<ServerEntity>

    /** 跨设备来源同步：按持久同步身份查本地来源（sync_source_id 唯一索引保证至多一条）。 */
    @Query("SELECT * FROM servers WHERE sync_source_id = :syncSourceId LIMIT 1")
    suspend fun getBySyncSourceId(syncSourceId: String): ServerEntity?

    /** 写回记录修订号（合并次序的唯一依据）。返回受影响行数。 */
    @Query("UPDATE servers SET sync_record_revision = :revision WHERE id = :serverId")
    suspend fun updateSyncRecordRevision(serverId: String, revision: Long): Int

    /** 写入同步身份三列（首次配对/导入落库时用）。返回受影响行数。 */
    @Query(
        "UPDATE servers SET sync_source_id = :syncSourceId, " +
            "sync_record_revision = :revision, sync_source_type_raw = :sourceTypeRaw WHERE id = :serverId",
    )
    suspend fun updateSyncIdentity(
        serverId: String,
        syncSourceId: String?,
        revision: Long,
        sourceTypeRaw: String?,
    ): Int

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
