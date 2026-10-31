package com.mediahub.core.common.sync

/**
 * 面向前端的计数摘要（对应 Windows SourceSyncPreviewSummary）。
 *
 * 三个"桶"与 Windows CLI plan 的 counts 同义，可用来做互操作对照：
 * - apply = Create + Update
 * - requiresApproval = 需要用户确认的条目数
 * - ignored = NoChange + IgnoreStale
 *
 * 注意：CLI 的 plan 是另一套"轮级"reason 码（apply-remote-change / tombstone-unknown /
 * already-applied），与本类的 reason 不是同一套；对照只能比桶的计数，不能比 reason 字符串。
 */
data class SourceSyncSummary(
    val total: Int = 0,
    val create: Int = 0,
    val update: Int = 0,
    val conflict: Int = 0,
    /** 仅统计 ambiguous-legacy-identity 造成的冲突（与 Windows 一致）。 */
    val ambiguous: Int = 0,
    val unsupported: Int = 0,
    val delete: Int = 0,
    val unchanged: Int = 0,
    val adopt: Int = 0,
    val requiresConfirmation: Int = 0,
) {
    /** CLI 的 counts.apply。 */
    val apply: Int get() = create + update

    /** CLI 的 counts.ignored。 */
    val ignored: Int get() = unchanged

    /** 是否有任何会改变本机的动作（删除也算，因为它会移除数据）。 */
    val hasChanges: Boolean get() = create + update + delete > 0

    companion object {
        fun fromPlan(plan: SourceSyncPlan): SourceSyncSummary = fromCandidates(plan.items)

        fun fromCandidates(candidates: List<SourceSyncCandidate>): SourceSyncSummary {
            var create = 0
            var update = 0
            var conflict = 0
            var ambiguous = 0
            var unsupported = 0
            var delete = 0
            var unchanged = 0
            var adopt = 0
            for (candidate in candidates) {
                when (candidate.action) {
                    SourceSyncAction.CREATE -> create++
                    SourceSyncAction.UPDATE -> update++
                    SourceSyncAction.CONFLICT -> {
                        conflict++
                        if (candidate.reason == SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY) ambiguous++
                    }
                    SourceSyncAction.UNSUPPORTED -> unsupported++
                    SourceSyncAction.DELETE -> delete++
                    SourceSyncAction.NO_CHANGE, SourceSyncAction.IGNORE_STALE -> unchanged++
                    SourceSyncAction.ADOPT -> adopt++
                }
            }
            return SourceSyncSummary(
                total = candidates.size,
                create = create,
                update = update,
                conflict = conflict,
                ambiguous = ambiguous,
                unsupported = unsupported,
                delete = delete,
                unchanged = unchanged,
                adopt = adopt,
                requiresConfirmation = candidates.count { it.requiresUserApproval },
            )
        }
    }
}
