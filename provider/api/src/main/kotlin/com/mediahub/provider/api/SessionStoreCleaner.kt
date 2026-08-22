package com.mediahub.provider.api

/**
 * 会话元数据清理器（删除媒体源时的级联清理面，Server Editor RemoveServerUseCase 使用）。
 *
 * 生产实现由 app 层把 Emby 的 EmbySessionStore 包装进来（Provider 会话元数据非敏感，
 * 但删除媒体源后应一并清除，避免留下孤儿 session 记录）。
 */
fun interface SessionStoreCleaner {
    suspend fun clear(serverId: String)
}
