package com.mediahub.core.common.sync

/** 基线存储给出的稳定原因码。 */
object SourceSyncBaselineStoreReasons {
    /** 进来的条目修订号比已存的小；已存的赢。 */
    const val STALE_REVISION: String = "baseline-stale-revision"
    /** 两条条目声称同一修订号却内容不同；保留已存的。 */
    const val UNRESOLVED_CONFLICT: String = "baseline-unresolved-conflict"
    /** 文件存在但读不出来（被占用、无权限）；没有隔离任何东西。 */
    const val STORE_UNREADABLE: String = "store-unreadable"
    /** 其余步骤都成功后，基线仍然落不了盘。 */
    const val WRITE_FAILED: String = "baseline-write-failed"
}

/**
 * 一次保存对已存基线做了什么。
 *
 * 保存是合并而不是替换：存储已经知道对端当时是什么样，某一轮学到的更少时，绝不能把基线往回拨。
 * 被这条守卫拒绝的条目按 id 报出来，调用方因此可以记日志，而不是默默以为这一轮已经落盘。
 */
data class SourceSyncBaselineSaveResult(
    /** 已存基线是否变了。 */
    val saved: Boolean = false,
    /** 进来的基线什么都没加、也没写介质。 */
    val unchanged: Boolean = false,
    /** 修订号守卫不允许覆盖已存内容的那些条目。 */
    val rejectedSyncSourceIds: List<String> = emptyList(),
    /** 第一条拒绝原因（若有）。 */
    val reason: String? = null,
) {
    /** 被修订号守卫拒绝的条目数。 */
    val rejectedCount: Int get() = rejectedSyncSourceIds.size

    companion object {
        /** 什么都没加的基线：没有发生写入。 */
        val NO_CHANGE: SourceSyncBaselineSaveResult = SourceSyncBaselineSaveResult(unchanged = true)

        /** 确实改变了存储的基线。 */
        fun written(
            rejectedSyncSourceIds: List<String>? = null,
            reason: String? = null,
        ): SourceSyncBaselineSaveResult = SourceSyncBaselineSaveResult(
            saved = true,
            rejectedSyncSourceIds = rejectedSyncSourceIds ?: emptyList(),
            reason = reason,
        )
    }
}

/**
 * 持久化「本机上一轮结束时认为每个对端是什么样」。
 *
 * 按对端 deviceId 分桶，桶内再按 syncSourceId：两个对端从不共享一份基线，
 * 所以与设备 B 的一轮不会让设备 A 显得已是最新。基线是关于对端的事实，永远不是本机的，
 * 因此缺一条记录时退化成「什么都不知道」，而不是猜。
 *
 * [load] 永不抛：文件缺失、文件损坏、文件由更新版本写出，统统返回 [SourceSyncBaseline.EMPTY]，
 * 下一轮就干脆发全量快照。[save] 在介质写不动时可以抛 [java.io.IOException]；
 * 把那个异常吞掉会让调用方以为这一轮已经落盘。
 */
interface ISourceSyncBaselineStore {
    /** 某个对端已存的基线，什么都不知道时返回 [SourceSyncBaseline.EMPTY]。 */
    fun load(peerDeviceId: String): SourceSyncBaseline

    /** 合并一份新基线，拒绝让任何条目倒退；报告被拒的条目。 */
    fun save(peerDeviceId: String, baseline: SourceSyncBaseline): SourceSyncBaselineSaveResult

    /**
     * 忘掉这个对端的全部基线；真的删了返回 true，什么都不知道返回 false。
     *
     * 忘记是**显式删除**，不是合并：[save] 只能让基线上行，一个被用户解绑的对端会永远留在文档里，
     * 让下一轮把重新配对回来的同一台设备当成「已经应用过」——用过期知识静默跳过真实变更。
     *
     * 这是本机的记账：不对对端说任何话，也不碰任何来源。空白身份是调用方的 bug（抛 [IllegalArgumentException]），
     * 不是「没有」这个答案。存储写不动时必须抛，而不是返回 false：一次「忘记」之后基线还在盘上，
     * 比一次失败的忘记更糟。
     */
    fun forget(peerDeviceId: String): Boolean
}

/** 把进来的基线合并进已存基线的结果。 */
data class SourceSyncBaselineMergeResult(
    /** 应该被存下来的基线。 */
    val merged: SourceSyncBaseline = SourceSyncBaseline.EMPTY,
    /** 进来的基线试图倒退或横移的条目。 */
    val rejectedSyncSourceIds: List<String> = emptyList(),
    /** 第一条拒绝原因。 */
    val reason: String? = null,
    /** [merged] 是否与已存的那份不同。 */
    val changed: Boolean = false,
)

/**
 * 修订号单调的合并，所有基线存储共用（对齐 Windows SourceSyncBaselineMerge）。
 *
 * 守卫故意保守：修订号更高的替换，修订号相同且哈希一致的当作无事，其余一律拒绝并给出原因。
 * 因为先后只由修订号承载、从不看墙上时钟，「拒绝」在这里的意思是「两台设备还需要再来一轮」，
 * 不是「后编辑的人输了」。
 */
object SourceSyncBaselineMerge {

    /** 把 [incoming] 合并进 [existing]。 */
    fun merge(existing: SourceSyncBaseline?, incoming: SourceSyncBaseline?): SourceSyncBaselineMergeResult {
        val entries = ArrayList<SourceSyncBaselineEntry>()
        val index = HashMap<String, Int>()
        for (entry in existing?.entries ?: emptyList()) {
            if (WireText.isBlank(entry.syncSourceId)) continue
            val key = WireText.trim(entry.syncSourceId)
            val lookup = key.lowercaseKey()
            if (index.containsKey(lookup)) continue
            index[lookup] = entries.size
            entries.add(entry.copy(syncSourceId = key))
        }

        val rejected = ArrayList<String>()
        var reason: String? = null

        for (entry in incoming?.entries ?: emptyList()) {
            if (WireText.isBlank(entry.syncSourceId)) continue
            val key = WireText.trim(entry.syncSourceId)
            val lookup = key.lowercaseKey()
            val candidate = entry.copy(syncSourceId = key)
            val position = index[lookup]
            if (position == null) {
                index[lookup] = entries.size
                entries.add(candidate)
                continue
            }

            val stored = entries[position]
            if (candidate.recordRevision > stored.recordRevision) {
                entries[position] = candidate
                continue
            }
            if (candidate.recordRevision == stored.recordRevision && sameContent(stored, candidate)) {
                continue
            }

            rejected.add(key)
            if (reason == null) {
                reason = if (candidate.recordRevision < stored.recordRevision) {
                    SourceSyncBaselineStoreReasons.STALE_REVISION
                } else {
                    SourceSyncBaselineStoreReasons.UNRESOLVED_CONFLICT
                }
            }
        }

        val incomingDeviceId = incoming?.deviceId
        val incomingSnapshotId = incoming?.snapshotId
        val merged = SourceSyncBaseline(
            version = SourceSyncBaseline.CURRENT_VERSION,
            deviceId = if (WireText.isBlank(incomingDeviceId)) existing?.deviceId else incomingDeviceId,
            snapshotId = if (WireText.isBlank(incomingSnapshotId)) existing?.snapshotId else incomingSnapshotId,
            entries = entries,
        )

        return SourceSyncBaselineMergeResult(
            merged = merged,
            rejectedSyncSourceIds = rejected,
            reason = reason,
            changed = !sameEntries(existing?.entries, entries),
        )
    }

    /** 两份基线是否描述完全相同的对端状态（忽略标签）。 */
    fun sameEntries(
        left: List<SourceSyncBaselineEntry>?,
        right: List<SourceSyncBaselineEntry>?,
    ): Boolean {
        val first = left ?: emptyList()
        val second = right ?: emptyList()
        if (first.size != second.size) return false
        for (position in first.indices) {
            if (!sameContent(first[position], second[position])) return false
        }
        return true
    }

    /** 两条条目是否描述同一身份、同一修订号、同一内容。 */
    fun sameContent(left: SourceSyncBaselineEntry?, right: SourceSyncBaselineEntry?): Boolean {
        // Kotlin 这里要显式判空：平台类型之外没有「null 也算相等」的语义。
        if (left == null || right == null) return false
        if (!WireText.equalsAsciiIgnoreCase(WireText.trim(left.syncSourceId), WireText.trim(right.syncSourceId))) {
            return false
        }
        if (left.recordRevision != right.recordRevision || left.fieldVersion != right.fieldVersion) return false
        if (left.contentHash != right.contentHash) return false
        val first = left.fieldHashes
        val second = right.fieldHashes
        if (first.size != second.size) return false
        for ((field, hash) in first) {
            val other = second[field] ?: return false
            if (hash != other) return false
        }
        return true
    }
}
