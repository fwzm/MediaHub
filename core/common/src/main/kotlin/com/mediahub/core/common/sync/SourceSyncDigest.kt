package com.mediahub.core.common.sync

import kotlinx.serialization.json.JsonNull

/**
 * 一条记录相对上次基线的三种变化（与 Windows SourceSyncChangeKind 一一对应）。
 *
 * 顺序值（ADDED=0 / UPDATED=1 / REMOVED=2）与 Windows 相同，但跨语言传输只传名字，不传序号。
 */
enum class SourceSyncChangeKind {
    ADDED,
    UPDATED,
    REMOVED,
}

/** 生成摘要时发现的、无法进入条目的问题。 */
data class SourceSyncDigestIssue(
    val syncSourceId: String = "",
    val reason: String,
)

/** 摘要问题码（与 Windows SourceSyncDigestReasons 逐字相同）。 */
object SourceSyncDigestReasons {

    /** 记录或墓碑的 syncSourceId 缺失/空白：无法定位，只能记问题。 */
    const val MISSING_SYNC_SOURCE_ID = "missing-sync-source-id"

    /** 记录没有 wire 类型值：发出去对方也无法解析。 */
    const val NO_WIRE_TYPE = "no-wire-type"

    /** 同一份摘要里同一个 syncSourceId 出现两次（大小写不敏感）。 */
    const val DUPLICATE_LOCAL_SYNC_ID = "duplicate-local-sync-id"

    /** 墓碑指向一条活着的记录：两个条目互相矛盾，不猜哪个对。 */
    const val TOMBSTONE_FOR_EXISTING_RECORD = "tombstone-for-existing-record"

    /** 全部问题码，按 Windows 声明顺序。 */
    val ALL: List<String> = listOf(
        MISSING_SYNC_SOURCE_ID,
        NO_WIRE_TYPE,
        DUPLICATE_LOCAL_SYNC_ID,
        TOMBSTONE_FOR_EXISTING_RECORD,
    )
}

/**
 * 一条变化：哪个来源、哪种变化、带着记录或墓碑、改了哪些字段。
 *
 * - [changedFields] 只在 Updated 时非空，元素取自 [SourceSyncContentHash.FIELDS]；
 * - [contentChanged] 表示内容哈希相对基线变了（基线里没有哈希时也算变）；
 * - [recordRevision] / [fieldVersion] 在 Removed 时来自墓碑。
 */
data class SourceSyncDigestItem(
    val syncSourceId: String,
    val kind: SourceSyncChangeKind,
    val record: SourceRecord? = null,
    val tombstone: SourceTombstone? = null,
    val changedFields: List<String> = emptyList(),
    val contentChanged: Boolean = false,
    val recordRevision: Long = 0L,
    val fieldVersion: Int = 0,
)

/**
 * 一份摘要：本端（或对端信封）相对上次基线**变了什么**。
 *
 * 三处语义与 Windows 逐条对齐：
 * 1. [isFullSnapshot] 仅当没有基线（首次接触或基线为空）时为 true —— 此时每条记录都是 Added；
 * 2. 没变的记录（内容、修订号、字段版本都与基线一致）不进 [items]；对端修订号更高时也不进（对方领先）；
 * 3. 有问题的记录（缺身份、重复、缺 wire 值、墓碑撞活记录）只进 [issues]，绝不悄悄丢掉。
 *
 * 本类型只做纯计算，不读时钟、不写存储。
 */
data class SourceSyncDigest(
    val items: List<SourceSyncDigestItem> = emptyList(),
    val issues: List<SourceSyncDigestIssue> = emptyList(),
    val isFullSnapshot: Boolean = false,
) {

    /** 没有任何变化（问题不算变化）。 */
    val isEmpty: Boolean get() = items.isEmpty()

    val added: List<SourceSyncDigestItem> get() = items.filter { it.kind == SourceSyncChangeKind.ADDED }

    val updated: List<SourceSyncDigestItem> get() = items.filter { it.kind == SourceSyncChangeKind.UPDATED }

    val removed: List<SourceSyncDigestItem> get() = items.filter { it.kind == SourceSyncChangeKind.REMOVED }

    /**
     * 把摘要重新装回一封「只含变化」的信封。
     *
     * 与 Windows 相同：[SourceSyncChangeKind.REMOVED] 进 deletions，Added/Updated 进 sources；
     * capabilities.sourceTypes 按 wire 值**首次出现顺序**去重（大小写不敏感）；本层不读时钟。
     */
    fun toEnvelope(
        deviceId: String,
        snapshotId: String,
        createdAtEpochMs: Long,
        canSendSecrets: Boolean = false,
    ): SourceSyncEnvelope {
        val sources = ArrayList<SourceRecord>()
        val deletions = ArrayList<SourceTombstone>()
        val sourceTypes = ArrayList<String>()
        val seenTypes = HashSet<String>()
        for (item in items) {
            when (item.kind) {
                SourceSyncChangeKind.REMOVED -> {
                    val tombstone = item.tombstone
                    if (tombstone != null) deletions.add(tombstone)
                }

                else -> {
                    val record = item.record
                    if (record != null) {
                        sources.add(record)
                        val wire = record.sourceType.wire
                        if (seenTypes.add(wire.lowercaseKey())) sourceTypes.add(wire)
                    }
                }
            }
        }
        return SourceSyncEnvelope(
            deviceId = WireText.trim(deviceId),
            snapshotId = snapshotId,
            createdAtEpochMs = createdAtEpochMs,
            capabilities = SyncCapabilities(canSendSecrets = canSendSecrets, sourceTypes = sourceTypes),
            sources = sources,
            deletions = deletions,
        )
    }

    companion object {

        /** 空摘要（没有变化，也不是全量）。 */
        val EMPTY: SourceSyncDigest = SourceSyncDigest()

        /**
         * 计算记录集合相对基线的变化。
         *
         * 判定顺序与 Windows SourceSyncDigest.Build 逐行一致：身份缺失 → 重复 → 缺 wire 值 →
         * 基线里没有（Added）→ 内容与修订都没变（跳过）→ 对端领先（跳过）→ Updated。
         * 记录列表中允许出现 null（Windows 也跳过），便于跨语言向量逐条对照。
         */
        fun build(
            records: List<SourceRecord?>,
            baseline: SourceSyncBaseline?,
            deletions: List<SourceTombstone?> = emptyList(),
        ): SourceSyncDigest {
            val fullSnapshot = baseline == null || baseline.isEmpty
            val items = ArrayList<SourceSyncDigestItem>()
            val issues = ArrayList<SourceSyncDigestIssue>()
            val seen = HashSet<String>()

            for (record in records) {
                if (record == null) continue
                val identity = trimmedIdentity(record.syncSourceId)
                if (identity == null) {
                    issues.add(SourceSyncDigestIssue(reason = SourceSyncDigestReasons.MISSING_SYNC_SOURCE_ID))
                    continue
                }
                if (!seen.add(identity.lowercaseKey())) {
                    issues.add(SourceSyncDigestIssue(identity, SourceSyncDigestReasons.DUPLICATE_LOCAL_SYNC_ID))
                    continue
                }
                if (record.sourceType.wire.isEmpty()) {
                    issues.add(SourceSyncDigestIssue(identity, SourceSyncDigestReasons.NO_WIRE_TYPE))
                    continue
                }

                val known = baseline?.find(identity)
                if (known == null) {
                    items.add(
                        SourceSyncDigestItem(
                            syncSourceId = identity,
                            kind = SourceSyncChangeKind.ADDED,
                            record = record,
                            changedFields = SourceSyncContentHash.FIELDS,
                            contentChanged = true,
                            recordRevision = record.recordRevision,
                            fieldVersion = record.fieldVersion,
                        ),
                    )
                    continue
                }

                val fieldHashes = SourceSyncContentHash.computeFields(record)
                val contentHash = SourceSyncContentHash.compute(record)
                val changedFields = changedFields(known, fieldHashes)
                val contentChanged = known.contentHash.isEmpty() || contentHash != known.contentHash
                if (!contentChanged && record.recordRevision == known.recordRevision && record.fieldVersion == known.fieldVersion) {
                    continue
                }
                // 对端（这里是基线）修订号更高：本地这份是旧的，推出去只会被对方再拒一次。
                if (record.recordRevision < known.recordRevision) continue

                items.add(
                    SourceSyncDigestItem(
                        syncSourceId = identity,
                        kind = SourceSyncChangeKind.UPDATED,
                        record = record,
                        changedFields = changedFields,
                        contentChanged = contentChanged,
                        recordRevision = record.recordRevision,
                        fieldVersion = record.fieldVersion,
                    ),
                )
            }

            for (tombstone in deletions) {
                if (tombstone == null) continue
                val identity = trimmedIdentity(tombstone.syncSourceId)
                if (identity == null) {
                    issues.add(SourceSyncDigestIssue(reason = SourceSyncDigestReasons.MISSING_SYNC_SOURCE_ID))
                    continue
                }
                if (seen.contains(identity.lowercaseKey())) {
                    issues.add(SourceSyncDigestIssue(identity, SourceSyncDigestReasons.TOMBSTONE_FOR_EXISTING_RECORD))
                    continue
                }
                if (!seen.add(identity.lowercaseKey())) {
                    issues.add(SourceSyncDigestIssue(identity, SourceSyncDigestReasons.DUPLICATE_LOCAL_SYNC_ID))
                    continue
                }
                items.add(
                    SourceSyncDigestItem(
                        syncSourceId = identity,
                        kind = SourceSyncChangeKind.REMOVED,
                        tombstone = tombstone,
                        recordRevision = tombstone.recordRevision,
                    ),
                )
            }

            return SourceSyncDigest(items = items, issues = issues, isFullSnapshot = fullSnapshot)
        }

        /** 从一封信封算「相对基线变了什么」（Windows 同名方法）。 */
        fun fromEnvelope(envelope: SourceSyncEnvelope, baseline: SourceSyncBaseline?): SourceSyncDigest =
            build(envelope.sources, baseline, envelope.deletions)

        /** 逐字段比对基线里记的哈希与本轮算出的哈希，返回变了的字段名（顺序取 [SourceSyncContentHash.FIELDS]）。 */
        private fun changedFields(
            known: SourceSyncBaselineEntry,
            fieldHashes: Map<String, String>,
        ): List<String> {
            val changed = ArrayList<String>()
            for (field in SourceSyncContentHash.FIELDS) {
                val knownHash = known.fieldHashes[field]
                val currentHash = fieldHashes[field]
                if (knownHash == null || currentHash == null || knownHash != currentHash) changed.add(field)
            }
            return changed
        }

        private fun trimmedIdentity(value: String?): String? {
            if (value == null || WireText.isBlank(value)) return null
            return WireText.trim(value)
        }
    }
}
