package com.mediahub.core.database.repository

import com.mediahub.model.MediaServer
import kotlinx.coroutines.flow.Flow

/**
 * 服务器读取接口（可测性抽象，FINAL PATCH 4）。
 * [ServerRepository] 是其唯一生产实现；测试可用内存 fake。
 */
interface ServerStore {
    fun observeServers(): Flow<List<MediaServer>>
    suspend fun getServer(id: String): MediaServer?
}
