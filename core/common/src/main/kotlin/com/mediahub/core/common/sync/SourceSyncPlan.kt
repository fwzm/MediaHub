package com.mediahub.core.common.sync

/** 一次规划的完整结果（对应 Windows SourceSyncPlan）。 */
data class SourceSyncPlan(val items: List<SourceSyncCandidate>) {

    /** 需要用户确认的条目。 */
    val requiringApproval: List<SourceSyncCandidate> get() = items.filter { it.requiresUserApproval }

    /** 冲突条目（含歧义身份）。 */
    val conflicts: List<SourceSyncCandidate> get() = items.filter { it.action == SourceSyncAction.CONFLICT }

    /** 某类动作的条目数。 */
    fun countOf(action: SourceSyncAction): Int = items.count { it.action == action }

    /** 建议真正写库的条目（Create/Update/Adopt）。 */
    val effective: List<SourceSyncCandidate>
        get() = items.filter {
            it.action == SourceSyncAction.CREATE ||
                it.action == SourceSyncAction.UPDATE ||
                it.action == SourceSyncAction.ADOPT
        }
}
