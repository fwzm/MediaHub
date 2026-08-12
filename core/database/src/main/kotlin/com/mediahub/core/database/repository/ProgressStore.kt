package com.mediahub.core.database.repository

import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow

/**
 * 播放进度读取接口（可测性抽象，FINAL PATCH 4）。
 * Phase 1B-2.1 补充 getResume/save（播放页解析续播位置与本地快照写入）。
 */
interface ProgressStore {
    fun observeContinueWatching(limit: Int = 30): Flow<List<PlaybackProgress>>
    suspend fun getResume(serverId: String, itemId: String): Long?
    suspend fun save(progress: PlaybackProgress)
}
