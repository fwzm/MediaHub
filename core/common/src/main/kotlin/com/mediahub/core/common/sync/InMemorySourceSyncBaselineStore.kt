package com.mediahub.core.common.sync

/**
 * 内存版基线存储：既是文件存储要对照的参考行为，也是测试替身（对齐 Windows InMemorySourceSyncBaselineStore）。
 *
 * [writeCount] 只统计真正改变了已存基线的事件（保存与忘记都算，对齐 Windows 两个存储的口径），
 * 所以测试可以断言「重复的一轮什么都没写」「忘记一台不存在的对端什么都没写」，而不是靠 getter 反推。
 */
class InMemorySourceSyncBaselineStore : ISourceSyncBaselineStore {

    private val gate = Any()
    private val peers = LinkedHashMap<String, SourceSyncBaseline>()

    /** 真正改变了已存基线的保存次数。 */
    var writeCount: Int = 0
        private set

    /** 本存储认识的对端，按插入顺序。 */
    val knownPeerDeviceIds: List<String>
        get() = synchronized(gate) { peers.keys.toList() }

    override fun load(peerDeviceId: String): SourceSyncBaseline {
        if (WireText.isBlank(peerDeviceId)) {
            return SourceSyncBaseline.EMPTY
        }
        synchronized(gate) {
            val key = WireText.trim(peerDeviceId)
            return peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }?.value
                ?: SourceSyncBaseline.EMPTY
        }
    }

    override fun save(peerDeviceId: String, baseline: SourceSyncBaseline): SourceSyncBaselineSaveResult {
        require(!WireText.isBlank(peerDeviceId)) { "A peer device id is required." }
        synchronized(gate) {
            val key = WireText.trim(peerDeviceId)
            val stored = peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }
            val existing = stored?.value
            val merge = SourceSyncBaselineMerge.merge(existing, baseline)
            if (!merge.changed) {
                return if (merge.rejectedSyncSourceIds.isEmpty()) {
                    SourceSyncBaselineSaveResult.NO_CHANGE
                } else {
                    SourceSyncBaselineSaveResult(
                        unchanged = true,
                        rejectedSyncSourceIds = merge.rejectedSyncSourceIds,
                        reason = merge.reason,
                    )
                }
            }
            peers[stored?.key ?: key] = merge.merged
            writeCount += 1
            return SourceSyncBaselineSaveResult.written(merge.rejectedSyncSourceIds, merge.reason)
        }
    }

    override fun forget(peerDeviceId: String): Boolean {
        require(!WireText.isBlank(peerDeviceId)) { "A peer device id is required." }
        synchronized(gate) {
            val key = WireText.trim(peerDeviceId)
            val stored = peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) } ?: return false
            peers.remove(stored.key)
            writeCount += 1
            return true
        }
    }
}
