package com.mediahub.core.common.sync

/** 一条轮次决定把数据往哪个方向搬（与 Windows SyncMergeDirection 同序）。 */
enum class SyncMergeDirection {
    /** 本机的改动会写到对端。 */
    PUSH,

    /** 对端的改动会写到本机。 */
    PULL,

    /** 两侧都改了同一个来源，单靠一个方向不够。 */
    BOTH,
}

/**
 * 一轮双向合并里对某一个来源的决定（Windows SyncRoundEntry 的 Kotlin 对等物）。
 *
 * 这是「决定的描述」而不是「已经做了」：只有 [SyncRoundResult.apply] 里的条目可以不经确认直接写；
 * [SyncRoundResult.requiresApproval] 里的必须等人回答；[SyncRoundResult.ignored] 里的不用动作。
 */
data class SyncRoundEntry(
    val syncSourceId: String,
    val direction: SyncMergeDirection,
    val kind: SourceSyncChangeKind,
    val reason: String = "",
    val record: SourceRecord? = null,
    val tombstone: SourceTombstone? = null,
    val conflictingFields: List<String> = emptyList(),
    val localRevision: Long = 0L,
    val remoteRevision: Long = 0L,
    val requiresApproval: Boolean = false,
)

/** 轮次原因码（与 Windows SyncRoundReasons 逐字相同，18 个）。 */
object SyncRoundReasons {

    const val PUSH_LOCAL_CHANGE = "push-local-change"
    const val PUSH_LOCAL_DELETE = "push-local-delete"
    const val APPLY_REMOTE_CHANGE = "apply-remote-change"
    const val ALREADY_APPLIED = "already-applied"
    const val STALE_REMOTE = "stale-remote"
    const val STALE_LOCAL = "stale-local"
    const val BOTH_DELETED = "both-deleted"
    const val DELETE_VS_UPDATE = "delete-vs-update"
    const val UPDATE_VS_DELETE = "update-vs-delete"
    const val FIELD_CONFLICT = "field-conflict"
    const val SAME_REVISION_DIFFERENT_CONTENT = "same-revision-different-content"
    const val REMOTE_NEWER = "remote-newer"
    const val LOCAL_NEWER = "local-newer"
    const val UNKNOWN_SOURCE_TYPE = "unknown-source-type"

    /** 码值继承 Windows 的拼写：协议码不因端而异。Android 能表示全部 12 种类型，所以这条在 Android 上几乎不会命中。 */
    const val NOT_SUPPORTED = "not-supported-on-windows"

    const val SECRETS_REQUIRE_APPROVAL = "secrets-require-approval"
    const val TOMBSTONE_REQUIRES_APPROVAL = "tombstone-requires-approval"
    const val TOMBSTONE_UNKNOWN = "tombstone-unknown"

    /** 全部码，按 Windows 声明顺序（向量按此序断言）。 */
    val ALL: List<String> = listOf(
        PUSH_LOCAL_CHANGE, PUSH_LOCAL_DELETE, APPLY_REMOTE_CHANGE, ALREADY_APPLIED,
        STALE_REMOTE, STALE_LOCAL, BOTH_DELETED, DELETE_VS_UPDATE, UPDATE_VS_DELETE,
        FIELD_CONFLICT, SAME_REVISION_DIFFERENT_CONTENT, REMOTE_NEWER, LOCAL_NEWER,
        UNKNOWN_SOURCE_TYPE, NOT_SUPPORTED, SECRETS_REQUIRE_APPROVAL,
        TOMBSTONE_REQUIRES_APPROVAL, TOMBSTONE_UNKNOWN,
    )
}

/**
 * 一轮双向合并的结果：三个桶互不重叠地划分了全部决定。
 *
 * [create] 会把「同一个来源同时出现在两个桶」变成异常 —— 否则一个来源可能既被写入又被挂起等人确认，
 * 「审阅」界面就会静默地直接生效。
 */
data class SyncRoundResult(
    val apply: List<SyncRoundEntry> = emptyList(),
    val requiresApproval: List<SyncRoundEntry> = emptyList(),
    val ignored: List<SyncRoundEntry> = emptyList(),
) {

    /** 全部条目，按桶顺序。 */
    val entries: List<SyncRoundEntry> get() = apply + requiresApproval + ignored

    /** 有没有可以不经确认直接写的工作。 */
    val hasWork: Boolean get() = apply.isNotEmpty()

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 按身份找条目（trim + 大小写不敏感）。 */
    fun find(syncSourceId: String?): SyncRoundEntry? {
        if (syncSourceId == null || WireText.isBlank(syncSourceId)) return null
        val key = WireText.trim(syncSourceId)
        return entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.syncSourceId, key) }
    }

    /** 某个原因码下的全部条目。 */
    fun withReason(reason: String): List<SyncRoundEntry> = entries.filter { it.reason == reason }

    companion object {

        /** 空结果。 */
        val EMPTY: SyncRoundResult = SyncRoundResult()

        /** 组装结果；同一身份跨桶重复即抛异常（与 Windows 的守卫同义）。 */
        fun create(
            apply: List<SyncRoundEntry>,
            requiresApproval: List<SyncRoundEntry>,
            ignored: List<SyncRoundEntry>,
        ): SyncRoundResult {
            val seen = HashSet<String>()
            for (entry in apply + requiresApproval + ignored) {
                if (!seen.add(WireText.trim(entry.syncSourceId).lowercaseKey())) {
                    throw IllegalArgumentException(
                        "Sync round entry for " + entry.syncSourceId + " is in more than one bucket.",
                    )
                }
            }
            return SyncRoundResult(apply, requiresApproval, ignored)
        }
    }
}
