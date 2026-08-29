package com.mediahub.provider.api

/**
 * 会话元数据清理器（删除媒体源时的级联清理面，Server Editor RemoveServerUseCase 使用）。
 *
 * 生产实现由 app 层把各 Provider 的会话存取包装进来（Provider 会话元数据非敏感，
 * 但删除媒体源后应一并清除，避免留下孤儿 session 记录）。
 */
fun interface SessionStoreCleaner {
    suspend fun clear(serverId: String)
}

/**
 * 组合会话清理器（Phase 1G-A，ADR-039）：删除媒体源时按 [clear] 依次清理
 * 全部已注册 Provider 的会话元数据（Emby/Jellyfin/…）——单一 Provider 绑定会随
 * Provider 数量增长而遗漏，组合语义是唯一正确形态。
 */
class CompositeSessionStoreCleaner(
    private val cleaners: List<SessionStoreCleaner>,
) : SessionStoreCleaner {
    override suspend fun clear(serverId: String) {
        cleaners.forEach { it.clear(serverId) }
    }
}
