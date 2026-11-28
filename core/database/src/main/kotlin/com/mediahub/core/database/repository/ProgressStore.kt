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

    /**
     * 条目 72：读取单条本地快照。
     *
     * 已缓存 / 本地内容没有远端 PROGRESS 能力，本地快照是唯一记录；
     * 详情页靠它回显续播进度（否则播完回到详情页仍是"立即播放"）。
     */
    suspend fun get(serverId: String, itemId: String): PlaybackProgress?

    /**
     * 条目 63：清空全部本地播放记录，返回实际删除条数。
     *
     * 幂等：空表返回 0，不抛错。
     */
    suspend fun clearAll(): Int
}
