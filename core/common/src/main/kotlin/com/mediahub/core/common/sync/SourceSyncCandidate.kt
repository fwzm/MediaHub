package com.mediahub.core.common.sync

/**
 * 一条建议（对应 Windows SourceSyncCandidate）。
 *
 * sourceType 保留原始 wire 串而不是枚举：本端不认识的值也要能展示给用户（"已保存但暂不支持"），
 * 并原样写回存储，等将来版本接管（D3）。
 */
data class SourceSyncCandidate(
    val action: SourceSyncAction,
    val reason: String = "",
    val syncSourceId: String? = null,
    /** 命中的本地服务器 id；CREATE/UNSUPPORTED/ADOPT 之外的匹配类动作才有。 */
    val serverId: String? = null,
    val sourceType: String? = null,
    val isKnownSourceType: Boolean = false,
    val displayName: String? = null,
    /** 主端点规范化后的地址；null 表示"没有可拨号地址"。 */
    val normalizedUrl: String? = null,
    val remoteRevision: Long? = null,
    val localRevision: Long? = null,
    /** true 表示必须由用户逐条确认后才能落库（永不静默执行）。 */
    val requiresUserApproval: Boolean = false,
    /** 冲突/歧义时涉及的本地服务器 id，供 UI 让用户二选一。 */
    val candidateServerIds: List<String> = emptyList(),
)
