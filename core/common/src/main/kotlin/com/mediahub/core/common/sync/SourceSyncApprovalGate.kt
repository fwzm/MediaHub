package com.mediahub.core.common.sync

/**
 * 用户对一条待裁决条目做出的选择。
 *
 * 只有两种：放行与跳过。没有"部分放行"，也没有三态——一条来源要么整条写入，要么整条不动。
 */
enum class SourceSyncApprovalChoice {
    SKIP,
    ALLOW,
}

/**
 * 一条裁决：某个来源（按 syncSourceId 认，不按显示名、不按行号）被放行或被跳过。
 *
 * 身份比较一律 trim + ASCII 大小写不敏感，与 Windows [SourceSyncApprovalDecision] 一致。
 */
data class SourceSyncApprovalDecision(
    val syncSourceId: String,
    val choice: SourceSyncApprovalChoice,
) {
    companion object {
        fun allow(syncSourceId: String): SourceSyncApprovalDecision =
            SourceSyncApprovalDecision(syncSourceId, SourceSyncApprovalChoice.ALLOW)

        fun skip(syncSourceId: String): SourceSyncApprovalDecision =
            SourceSyncApprovalDecision(syncSourceId, SourceSyncApprovalChoice.SKIP)
    }
}

/**
 * 裁决界面的一行：一条来源为什么要写、要不要用户点头、显示什么。
 *
 * 两个工厂覆盖两条来路——单向导入的规划器候选（[fromCandidate]）、双向合并的一轮条目（[fromRoundEntry]）。
 * 行字段只用于显示；真正的判定在 [SourceSyncApprovalGate.decide] 里，只读三个字段：
 * syncSourceId、action、reason、requiresApproval。**绝不在这里比较版本号**，否则会出现规划器与闸门各判一次。
 */
data class SourceSyncApprovalItem(
    val syncSourceId: String = "",
    val reason: String = "",
    val action: SourceSyncAction = SourceSyncAction.NO_CHANGE,
    val requiresApproval: Boolean = false,
    val displayName: String? = null,
    val serverId: String? = null,
    val sourceType: String? = null,
    val normalizedUrl: String? = null,
    val localRevision: Long? = null,
    val remoteRevision: Long? = null,
) {
    companion object {
        /** 单向导入：规划器说了要做什么，闸门只负责"能不能落"。 */
        fun fromCandidate(candidate: SourceSyncCandidate): SourceSyncApprovalItem = SourceSyncApprovalItem(
            syncSourceId = WireText.trim(candidate.syncSourceId ?: ""),
            reason = candidate.reason,
            action = candidate.action,
            requiresApproval = candidate.requiresUserApproval,
            displayName = candidate.displayName,
            serverId = candidate.serverId,
            sourceType = candidate.sourceType,
            normalizedUrl = candidate.normalizedUrl,
            localRevision = candidate.localRevision,
            remoteRevision = candidate.remoteRevision,
        )

        /** 双向合并：一轮条目没有 action，只有 kind + reason，映射规则必须与 Windows 逐字相同。 */
        fun fromRoundEntry(entry: SyncRoundEntry): SourceSyncApprovalItem = SourceSyncApprovalItem(
            syncSourceId = WireText.trim(entry.syncSourceId),
            reason = entry.reason,
            action = actionOf(entry.kind, entry.reason),
            requiresApproval = entry.requiresApproval,
            displayName = entry.record?.displayName,
            serverId = null,
            sourceType = entry.record?.sourceType?.raw,
            normalizedUrl = primaryUrl(entry.record),
            localRevision = entry.localRevision,
            remoteRevision = entry.remoteRevision,
        )

        /**
         * Added/Updated/Removed 三种变更天然对应 Create/Update/Delete；
         * 其余（NoChange/IgnoreStale 之类的"没有变更"）只有两个原因码算 Unsupported，其他一律 NoChange。
         */
        private fun actionOf(kind: SourceSyncChangeKind, reason: String): SourceSyncAction = when (kind) {
            SourceSyncChangeKind.ADDED -> SourceSyncAction.CREATE
            SourceSyncChangeKind.UPDATED -> SourceSyncAction.UPDATE
            SourceSyncChangeKind.REMOVED -> SourceSyncAction.DELETE
            else ->
                if (
                    WireText.equalsAsciiIgnoreCase(reason, SyncRoundReasons.UNKNOWN_SOURCE_TYPE) ||
                    WireText.equalsAsciiIgnoreCase(reason, SyncRoundReasons.NOT_SUPPORTED)
                ) {
                    SourceSyncAction.UNSUPPORTED
                } else {
                    SourceSyncAction.NO_CHANGE
                }
        }

        /**
         * 主地址原样返回：标了 primary 的优先，否则 sortOrder 最小的；**不做归一化**（归一化是规划器的活，
         * 这里再归一化一次就会和规划器给出两个不同地址）。同 sortOrder 取先出现者，与 Windows OrderBy 的稳定性一致。
         */
        private fun primaryUrl(record: SourceRecord?): String? {
            val endpoints = record?.endpoints ?: return null
            if (endpoints.isEmpty()) return null
            val primary = endpoints.firstOrNull { it.isPrimary }
            val picked = primary ?: endpoints.withIndex().minByOrNull { it.value.sortOrder }?.value
            return picked?.url
        }
    }
}

/**
 * 闸门的裁决结果：三个互斥桶，加起来正好等于输入。
 *
 * 一条来源不可能既被写入又等着批准。被扣下的条目（[withheld]）是**任何点击都不能放行**的那类，
 * 它的原因码在 [blockingReasons] 里再列一次（按首次出现顺序去重），好让界面解释按钮为什么不动。
 */
data class SourceSyncApprovalResult(
    val applied: List<SourceSyncApprovalItem> = emptyList(),
    val skipped: List<SourceSyncApprovalItem> = emptyList(),
    val withheld: List<SourceSyncApprovalItem> = emptyList(),
    val blockingReasons: List<String> = emptyList(),
    val acknowledgedRisk: Boolean = false,
    val appliedWithRisk: Int = 0,
) {
    val canApply: Boolean get() = applied.isNotEmpty()
    val isBlocked: Boolean get() = withheld.isNotEmpty()
    val appliedCount: Int get() = applied.size
    val skippedCount: Int get() = skipped.size
    val withheldCount: Int get() = withheld.size
    val isEmpty: Boolean get() = applied.isEmpty() && skipped.isEmpty() && withheld.isEmpty()

    /** 在三个桶里按 trim + 大小写不敏感找一条。 */
    fun find(syncSourceId: String?): SourceSyncApprovalItem? {
        val key = identityOf(syncSourceId)
        return (applied + skipped + withheld).firstOrNull { identityOf(it.syncSourceId) == key }
    }

    fun isWithheld(syncSourceId: String?): Boolean = withheld.any { identityOf(it.syncSourceId) == identityOf(syncSourceId) }

    fun isApplied(syncSourceId: String?): Boolean = applied.any { identityOf(it.syncSourceId) == identityOf(syncSourceId) }

    override fun toString(): String =
        "Approval(applied=" + appliedCount + ", skipped=" + skippedCount + ", withheld=" + withheldCount +
            ", canApply=" + canApply + ", risk=" + acknowledgedRisk + ")"

    companion object {
        val EMPTY: SourceSyncApprovalResult = SourceSyncApprovalResult()

        private fun identityOf(value: String?): String = WireText.lowercaseAscii(WireText.trim(value ?: ""))
    }
}

/**
 * 批准闸门：把"规划器认为该写"翻译成"现在允许写"。
 *
 * 这是导入闭环里唯一能拦住写入的地方，规则与 Windows [SourceSyncApprovalGate] 逐条相同：
 *
 * 1. **有些原因永远不许放行**（凭据块、未知类型、本端不支持的类型）——即使调用方显式 Allow 也不放行，
 *    因为放行一条就意味着一份凭据落进本地存储，或者一种本地根本存不下的来源被写坏。
 * 2. 不是写入动作的条目（NoChange/IgnoreStale/Unsupported）直接进 skipped，用户点什么都不会变。
 * 3. 需要批准而没有收到决策 → 默认**跳过**（宁可少做，不可擅自写入）。
 * 4. 同一身份出现多条决策时，**后写的获胜**；同一身份既 allow 又 skip 时由调用方排序决定，闸门不猜。
 * 5. 空身份是调用方的 bug，不是用户的选择：直接抛，不静默丢弃。
 */
object SourceSyncApprovalGate {

    /** 模糊身份类原因的统一前缀（例如 ambiguous-legacy-identity）。 */
    const val AMBIGUOUS_REASON_PREFIX: String = "ambiguous-"

    /** 永远不许放行的原因码（顺序与 Windows 一致）。 */
    val NEVER_ALLOWABLE: List<String> = listOf(
        SyncRoundReasons.SECRETS_REQUIRE_APPROVAL,
        SyncRoundReasons.UNKNOWN_SOURCE_TYPE,
        SyncRoundReasons.NOT_SUPPORTED,
    )

    /** 放行会覆盖/删除对端数据的风险原因码（顺序与 Windows 一致）。 */
    val RISKY: List<String> = listOf(
        SyncRoundReasons.DELETE_VS_UPDATE,
        SyncRoundReasons.UPDATE_VS_DELETE,
        SyncRoundReasons.FIELD_CONFLICT,
        SyncRoundReasons.SAME_REVISION_DIFFERENT_CONTENT,
    )

    fun isNeverAllowable(reason: String?): Boolean {
        val trimmed = WireText.trim(reason ?: "")
        return NEVER_ALLOWABLE.any { it == trimmed }
    }

    fun isRisky(reason: String?): Boolean {
        val trimmed = WireText.trim(reason ?: "")
        if (WireText.startsWithAsciiIgnoreCase(trimmed, AMBIGUOUS_REASON_PREFIX)) return true
        return RISKY.any { it == trimmed }
    }

    /** 会真的改动本地数据的动作。其余动作放行也没有意义。 */
    fun isApplicable(action: SourceSyncAction): Boolean = when (action) {
        SourceSyncAction.CREATE,
        SourceSyncAction.UPDATE,
        SourceSyncAction.DELETE,
        SourceSyncAction.ADOPT,
        SourceSyncAction.CONFLICT,
        -> true

        SourceSyncAction.NO_CHANGE,
        SourceSyncAction.IGNORE_STALE,
        SourceSyncAction.UNSUPPORTED,
        -> false
    }

    /** 单向导入的入口：规划器候选直接进闸门。 */
    fun decide(
        plan: SourceSyncPlan,
        decisions: List<SourceSyncApprovalDecision> = emptyList(),
    ): SourceSyncApprovalResult = decide(plan.items.map { SourceSyncApprovalItem.fromCandidate(it) }, decisions)

    /**
     * 双向合并的入口：**只取 apply 与 requiresApproval 两个桶**。
     *
     * ignored 桶根本不该进闸门——那些条目这一轮什么都不做，让它们出现在批准界面上只会误导用户。
     */
    fun decide(
        round: SyncRoundResult,
        decisions: List<SourceSyncApprovalDecision> = emptyList(),
    ): SourceSyncApprovalResult = decide(
        (round.apply + round.requiresApproval).map { SourceSyncApprovalItem.fromRoundEntry(it) },
        decisions,
    )

    fun decide(
        items: List<SourceSyncApprovalItem>,
        decisions: List<SourceSyncApprovalDecision> = emptyList(),
    ): SourceSyncApprovalResult {
        // 先收决策：后写的覆盖先写的，身份 trim + 大小写不敏感。
        val choices = HashMap<String, SourceSyncApprovalChoice>()
        for (decision in decisions) {
            val key = WireText.lowercaseAscii(WireText.trim(decision.syncSourceId))
            if (key.isEmpty()) {
                throw IllegalArgumentException("An approval decision needs a syncSourceId.")
            }
            choices[key] = decision.choice
        }

        val applied = ArrayList<SourceSyncApprovalItem>()
        val skipped = ArrayList<SourceSyncApprovalItem>()
        val withheld = ArrayList<SourceSyncApprovalItem>()
        val blocking = ArrayList<String>()
        var appliedWithRisk = 0
        var acknowledgedRisk = false

        for (item in items) {
            val key = WireText.lowercaseAscii(WireText.trim(item.syncSourceId))
            if (key.isEmpty()) {
                throw IllegalArgumentException("An approval item needs a syncSourceId.")
            }

            if (isNeverAllowable(item.reason)) {
                withheld.add(item)
                if (blocking.none { it == item.reason }) {
                    blocking.add(item.reason)
                }
                continue
            }

            if (!isApplicable(item.action)) {
                skipped.add(item)
                continue
            }

            val choice = choices[key]
            val allow = choice?.let { it == SourceSyncApprovalChoice.ALLOW } ?: !item.requiresApproval
            if (!allow) {
                skipped.add(item)
                continue
            }

            applied.add(item)
            if (item.requiresApproval) {
                appliedWithRisk += 1
                if (isRisky(item.reason)) {
                    acknowledgedRisk = true
                }
            }
        }

        return SourceSyncApprovalResult(
            applied = applied,
            skipped = skipped,
            withheld = withheld,
            blockingReasons = blocking,
            acknowledgedRisk = acknowledgedRisk,
            appliedWithRisk = appliedWithRisk,
        )
    }
}
