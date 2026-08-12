package com.mediahub.core.database.repository

import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow

/** 播放进度读取接口（可测性抽象，FINAL PATCH 4）。 */
interface ProgressStore {
    fun observeContinueWatching(limit: Int = 30): Flow<List<PlaybackProgress>>
}
