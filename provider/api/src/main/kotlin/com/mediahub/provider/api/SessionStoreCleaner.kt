package com.mediahub.provider.api

import kotlin.coroutines.cancellation.CancellationException

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
 * 每个持有会话元数据的 Provider 通过 @IntoSet 注册自己的清理实现，
 * app composition root **不枚举具体 Provider**——第三个带会话的 Provider 接入时
 * 零 app 层改动（与 Factory/@IntoSet 同一扩展方向）。
 */
interface ProviderSessionCleaner {
    suspend fun clear(serverId: String)
}

/**
 * 组合会话清理器（Phase 1G-A，ADR-039）：删除媒体源时按 [clear] 依次清理
 * 全部已注册 Provider 的会话元数据——单一 Provider 绑定会随 Provider 数量增长而遗漏，
 * 组合语义是唯一正确形态。
 *
 * 失败语义（清理必须"尽量全清"，不能"遇错即停"）：
 * - 每个 Provider 的清理互相隔离，前一个抛异常**不得**让后面的 Provider 跳过——
 *   否则删除媒体源后残留孤儿 session 记录，且这些记录再也无人清理；
 * - 首个失败**原样**抛给调用方（类型、实例、消息都不变，删除媒体源的日志行与
 *   RemoveServerFailedException.cause 契约因此不受影响），其余失败不再重复抛出；
 * - 失败 Provider 的身份以 [SessionCleanupFailure] 挂在首个失败的 suppressed 上，
 *   用于定位"是哪个 Provider 没清干净"；
 * - 协程取消原样透传并**立即停止**扇出（取消不是清理失败，不得被吞掉或附加诊断）。
 */
class CompositeSessionStoreCleaner(
    private val cleaners: Set<ProviderSessionCleaner>,
) : SessionStoreCleaner {
    override suspend fun clear(serverId: String) {
        var firstFailure: Throwable? = null
        var firstFailureOwner: String? = null
        for (cleaner in cleaners) {
            try {
                cleaner.clear(serverId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                    firstFailureOwner = cleaner.javaClass.name
                }
            }
        }
        val failure = firstFailure ?: return
        failure.addSuppressed(SessionCleanupFailure(serverId, firstFailureOwner.orEmpty()))
        throw failure
    }
}

/**
 * 会话清理失败诊断：点名失败 Provider 与被清理的 [serverId]。
 *
 * 它只作为首个失败的 suppressed 存在，**不是**抛给调用方的顶层异常——
 * 顶层异常保持原始类型与消息，删除媒体源的日志契约（点名真实失败原因）因此不变。
 */
class SessionCleanupFailure(
    val serverId: String,
    val providerCleaner: String,
) : IllegalStateException("会话清理未能完成：serverId=" + serverId + " provider=" + providerCleaner)
