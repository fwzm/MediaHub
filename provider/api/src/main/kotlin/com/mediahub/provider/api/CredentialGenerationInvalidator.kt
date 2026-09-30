package com.mediahub.provider.api

/**
 * 凭据"世代"失效抽象（联合候选 A2-5 对齐项）。
 *
 * 背景：Provider 可能实现各自的进程内凭据世代/租约机制（如 WebDAV 的
 * WebDavCredentialCoordinator）来保护"迟到失败不得误删较新身份的凭据"。
 * 备份恢复（ADR-041）替换身份时，除了 TokenStore 的 restore lease 与
 * CredentialVault 清理，还必须**显式推进**这些机制中的世代，否则同一
 * serverId 上的在途旧 handle 与恢复后的新身份之间会出现互不知情的窗口。
 *
 * 实现方通过 Hilt `@IntoSet` 绑定；消费方（恢复/移除身份的失效器）注入
 * `Set<CredentialGenerationInvalidator>` 逐个调用，不枚举具体 Provider。
 * 全部为进程内语义，不宣称跨进程事务。
 */
interface CredentialGenerationInvalidator {

    /** 推进 [serverId] 的凭据世代，使全部在途句柄的条件清理失效。 */
    suspend fun invalidateCredentialsGeneration(serverId: String)
}
