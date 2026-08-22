package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.PlaybackProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本地播放进度仓库。
 * 数据源说明：本地快照用于"继续观看"展示；权威进度在服务端（Provider 负责同步）。
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val db: AppDatabase,
) : ProgressStore {
    private val dao = db.playbackProgressDao()

    override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun save(progress: PlaybackProgress) {
        dao.upsert(progress.toEntity())
    }
    override suspend fun getResume(serverId: String, itemId: String): Long? =
        dao.get(serverId, itemId)?.positionMs

    suspend fun clear(serverId: String, itemId: String) {
        dao.delete(serverId, itemId)
    }

    /** 删除某服务器的全部本地进度（Server Editor 删除媒体源级联）。 */
    suspend fun deleteByServer(serverId: String) {
        dao.deleteByServer(serverId)
    }

    suspend fun cleanupOlderThan(beforeEpochMs: Long) {
        dao.deleteOlderThan(beforeEpochMs)
    }
}
