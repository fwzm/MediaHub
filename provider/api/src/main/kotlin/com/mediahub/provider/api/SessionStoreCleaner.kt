package com.mediahub.provider.api

/**
 * 会话元数据清理器（删除媒体源时的级联清理面，Server Editor RemoveServerUseCase 使用）。
 *
 * 生产实现由 app 层以 [CompositeSessionStoreCleaner] 聚合全部 [ProviderSessionCleaner]
 * 贡献者（Provider 会话元数据非敏感，但删除媒体源后应一并清除，避免留下孤儿 session 记录）。
 */
fun interface SessionStoreCleaner {
    suspend fun clear(serverId: String)
}

/**
 * Provider 侧会话清理贡献者（Phase 1G-A review hardening，ADR-039）：
 * 每个持有会话元数据的 Provider 通过 `@IntoSet` 注册自己的清理实现，
 * app composition root **不枚举具体 Provider**——第三个带会话的 Provider 接入时
 * 零 app 层改动（与 Factory/`@IntoSet` 同一扩展方向）。
 */
interface ProviderSessionCleaner {
    suspend fun clear(serverId: String)
}

/**
 * 组合会话清理器（Phase 1G-A，ADR-039）：删除媒体源时按 [clear] 依次清理
 * 全部已注册 Provider 的会话元数据——单一 Provider 绑定会随 Provider 数量增长而遗漏，
 * 组合语义是唯一正确形态。
 */
class CompositeSessionStoreCleaner(
    private val cleaners: Set<ProviderSessionCleaner>,
) : SessionStoreCleaner {
    override suspend fun clear(serverId: String) {
        cleaners.forEach { it.clear(serverId) }
    }
}
