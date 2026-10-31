package com.mediahub.core.common.sync

/**
 * 上一轮结束时，本机认为对端持有的某个来源的样子（W6-72，对齐 Windows SourceSyncBaselineEntry）。
 *
 * 修订号决定谁赢，字段版本决定两次编辑是否可比，哈希回答「内容变了没有」而不必搬运整条记录。
 * contentHash 是整条记录的哈希，fieldHashes 按字段拆开，好让摘要能点名到底是哪个字段动了，
 * 而不是直接提一次盲目的覆盖。
 */
data class SourceSyncBaselineEntry(
    /** 这条记录描述的跨设备身份。 */
    val syncSourceId: String = "",
    /** 对端当时持有的修订号。 */
    val recordRevision: Long = 0L,
    /** 对端当时持有的字段集版本。 */
    val fieldVersion: Int = 0,
    /** 整条记录的内容哈希。 */
    val contentHash: String = "",
    /** 按字段拆开的哈希。空表示「不知道」，不是「没变化」。 */
    val fieldHashes: Map<String, String> = emptyMap(),
) {
    companion object {
        /** 按记录当前的样子构造一条基线。 */
        fun fromRecord(record: SourceRecord): SourceSyncBaselineEntry = SourceSyncBaselineEntry(
            syncSourceId = WireText.trim(record.syncSourceId),
            recordRevision = record.recordRevision,
            fieldVersion = record.fieldVersion,
            contentHash = SourceSyncContentHash.compute(record),
            fieldHashes = SourceSyncContentHash.computeFields(record),
        )
    }
}

/**
 * 本机上一轮与某个对端收敛时，对端那份快照：每个 syncSourceId 一条 [SourceSyncBaselineEntry]。
 *
 * 基线是关于「对端」的事实，不是关于本机的：它说「对端当时是这个修订号、这份内容」。摘要算的是
 * 当前本地记录与这份事实之间的差，所以基线缺失时只能退化成全量快照，而不是猜。
 *
 * 这里不读时钟；deviceId 与 snapshotId 只是用来向用户解释某轮的标签，先后顺序只由修订号承载。
 */
data class SourceSyncBaseline(
    /** 本文档版本。 */
    val version: Int = CURRENT_VERSION,
    /** 基线描述的对端，已知时给出。 */
    val deviceId: String? = null,
    /** 基线最后一次从哪个快照推进而来，已知时给出；仅供说明。 */
    val snapshotId: String? = null,
    /** 每个已知来源一条，顺序为首次获知的顺序。 */
    val entries: List<SourceSyncBaselineEntry> = emptyList(),
) {
    /** 是否一条来源都不知道。 */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** 按身份查条目：先剥空白、再只折 ASCII 大小写比较。 */
    fun find(syncSourceId: String?): SourceSyncBaselineEntry? {
        if (WireText.isBlank(syncSourceId)) return null
        val key = WireText.trim(syncSourceId!!)
        return entries.firstOrNull { entry -> WireText.equalsAsciiIgnoreCase(entry.syncSourceId, key) }
    }

    companion object {
        /** 本构建写出与读取的文档版本。 */
        const val CURRENT_VERSION: Int = 1

        /** 什么都不知道的基线：任何本地记录都算新记录。 */
        val EMPTY: SourceSyncBaseline = SourceSyncBaseline()

        /** 把收到的信封里每条记录都记成基线。 */
        fun fromEnvelope(envelope: SourceSyncEnvelope): SourceSyncBaseline = SourceSyncBaseline(
            deviceId = envelope.deviceId,
            snapshotId = envelope.snapshotId,
            entries = deduplicate(envelope.sources),
        )

        /** 把一组记录记成基线。 */
        fun fromRecords(
            records: List<SourceRecord>,
            deviceId: String? = null,
            snapshotId: String? = null,
        ): SourceSyncBaseline = SourceSyncBaseline(
            deviceId = deviceId,
            snapshotId = snapshotId,
            entries = deduplicate(records),
        )

        private fun deduplicate(records: List<SourceRecord>): List<SourceSyncBaselineEntry> {
            val entries = ArrayList<SourceSyncBaselineEntry>(records.size)
            val seen = HashSet<String>(records.size)
            for (record in records) {
                if (WireText.isBlank(record.syncSourceId)) continue
                val key = WireText.trim(record.syncSourceId)
                // 一个身份一条：载荷里出现重复 id 属坏输入，保留首次是唯一不依赖到达顺序的确定性选择。
                if (!seen.add(key.lowercaseKey())) continue
                entries.add(SourceSyncBaselineEntry.fromRecord(record))
            }
            return entries
        }
    }
}

/** 身份比较用的键：剥空白 + 只折 ASCII（与两端 WireText 的大小写口径一致）。 */
internal fun String.lowercaseKey(): String = WireText.lowercaseAscii(WireText.trim(this))
