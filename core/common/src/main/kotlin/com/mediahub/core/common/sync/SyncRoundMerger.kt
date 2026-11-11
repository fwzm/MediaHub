package com.mediahub.core.common.sync

import kotlinx.serialization.json.JsonNull

/**
 * 把本端与对端各一份摘要合并成一轮决定的纯函数（Windows SyncRoundMerger 的 Kotlin 对等物）。
 *
 * 三条性质与 Windows 逐条一致：
 * 1. 永不写、永不读时钟 —— 只把「各自相对基线变了什么」划分成 应用/需确认/忽略 三个桶；
 * 2. 输出按 syncSourceId 的 **ordinal** 排序（不是输入顺序）：两台设备跑同一轮必须得到同一个列表；
 * 3. 修订号决定同一来源的两个版本谁胜，时间戳从不参与（D5）。
 *
 * 具体判定：「远端记录在这儿表示不出来」永远先于修订号比较；删本地来源一律需要确认；
 * 两侧同内容时取修订号大的一方；内容不同且撞到同一批字段（同一 fieldVersion）才算真冲突。
 */
object SyncRoundMerger {

    /** 合并一轮。
     *
     * @param local 本端相对 baseline 变了什么。
     * @param remote 对端相对同一 baseline 变了什么。
     * @param baseline 双方上次一致的状态；null 表示首次接触。
     */
    fun merge(
        local: SourceSyncDigest,
        remote: SourceSyncDigest,
        baseline: SourceSyncBaseline? = null,
    ): SyncRoundResult {
        val localById = index(local)
        val remoteById = index(remote)
        // 身份按 ASCII 大小写不敏感归并：'A' 与 'a' 是同一个来源，只能出一条决定 ——
        // 否则同一来源会同时进两个桶，SyncRoundResult.create 会直接抛异常。
        // 显示拼写取本端那一份（与 Windows 先插本端、对端 TryAdd 同口径）。
        val spellings = LinkedHashMap<String, String>()
        for (key in localById.keys) spellings[lowercaseIdentity(key)] = key
        for (key in remoteById.keys) {
            val identity = lowercaseIdentity(key)
            if (!spellings.containsKey(identity)) spellings[identity] = key
        }
        val keys = spellings.values.sorted()

        val apply = ArrayList<SyncRoundEntry>()
        val approval = ArrayList<SyncRoundEntry>()
        val ignored = ArrayList<SyncRoundEntry>()

        for (key in keys) {
            val decision = decide(key, lookup(localById, key), lookup(remoteById, key), baseline?.find(key))
            when (decision.bucket) {
                SyncRoundBucket.APPLY -> apply.add(decision.entry)
                SyncRoundBucket.APPROVAL -> approval.add(decision.entry)
                SyncRoundBucket.IGNORE -> ignored.add(decision.entry)
            }
        }

        return SyncRoundResult.create(apply, approval, ignored)
    }

    private fun decide(
        key: String,
        local: SourceSyncDigestItem?,
        remote: SourceSyncDigestItem?,
        known: SourceSyncBaselineEntry?,
    ): SyncRoundDecision {
        // 对端的记录必须先在本地表示得出来，修订号比较才有意义：
        // 本端解析不了的类型绝不能猜着写进去。
        val remoteRecord = remote?.record
        if (remote != null && remote.kind != SourceSyncChangeKind.REMOVED && remoteRecord != null) {
            val unsupported = describeUnsupported(remoteRecord)
            if (unsupported != null) {
                return approval(
                    key = key,
                    direction = SyncMergeDirection.PULL,
                    kind = remote.kind,
                    reason = unsupported,
                    remote = remote,
                    record = remoteRecord,
                )
            }
        }

        if (local == null) {
            return if (remote == null) {
                ignore(key, SyncMergeDirection.BOTH, SourceSyncChangeKind.UPDATED, SyncRoundReasons.ALREADY_APPLIED)
            } else {
                decideRemoteOnly(key, remote, known)
            }
        }

        if (remote == null) return decideLocalOnly(key, local, known)

        return decideBoth(key, local, remote)
    }

    private fun decideRemoteOnly(
        key: String,
        remote: SourceSyncDigestItem,
        known: SourceSyncBaselineEntry?,
    ): SyncRoundDecision {
        if (remote.kind == SourceSyncChangeKind.REMOVED) {
            val tombstone = tombstoneOf(remote, key)
            if (known == null) {
                return ignore(
                    key = key,
                    direction = SyncMergeDirection.PULL,
                    kind = remote.kind,
                    reason = SyncRoundReasons.TOMBSTONE_UNKNOWN,
                    remote = remote,
                    tombstone = tombstone,
                )
            }
            if (remote.recordRevision < known.recordRevision) {
                return ignore(
                    key = key,
                    direction = SyncMergeDirection.PULL,
                    kind = remote.kind,
                    reason = SyncRoundReasons.STALE_REMOTE,
                    remote = remote,
                    tombstone = tombstone,
                )
            }
            // 删本地来源永不可能自动进行，即使对端明显更新。
            return approval(
                key = key,
                direction = SyncMergeDirection.PULL,
                kind = remote.kind,
                reason = SyncRoundReasons.TOMBSTONE_REQUIRES_APPROVAL,
                remote = remote,
                tombstone = tombstone,
            )
        }

        if (known != null && remote.recordRevision <= known.recordRevision) {
            return ignore(
                key = key,
                direction = SyncMergeDirection.PULL,
                kind = remote.kind,
                reason = SyncRoundReasons.ALREADY_APPLIED,
                remote = remote,
                record = remote.record,
            )
        }

        return apply(
            key = key,
            direction = SyncMergeDirection.PULL,
            kind = remote.kind,
            reason = SyncRoundReasons.APPLY_REMOTE_CHANGE,
            remote = remote,
            record = remote.record,
        )
    }

    private fun decideLocalOnly(
        key: String,
        local: SourceSyncDigestItem,
        known: SourceSyncBaselineEntry?,
    ): SyncRoundDecision {
        if (local.kind == SourceSyncChangeKind.REMOVED) {
            val tombstone = tombstoneOf(local, key)
            if (known != null && local.recordRevision < known.recordRevision) {
                return ignore(
                    key = key,
                    direction = SyncMergeDirection.PUSH,
                    kind = local.kind,
                    reason = SyncRoundReasons.STALE_LOCAL,
                    local = local,
                    tombstone = tombstone,
                )
            }
            return apply(
                key = key,
                direction = SyncMergeDirection.PUSH,
                kind = local.kind,
                reason = SyncRoundReasons.PUSH_LOCAL_DELETE,
                local = local,
                tombstone = tombstone,
            )
        }

        return apply(
            key = key,
            direction = SyncMergeDirection.PUSH,
            kind = local.kind,
            reason = SyncRoundReasons.PUSH_LOCAL_CHANGE,
            local = local,
            record = local.record,
        )
    }

    private fun decideBoth(
        key: String,
        local: SourceSyncDigestItem,
        remote: SourceSyncDigestItem,
    ): SyncRoundDecision {
        if (local.kind == SourceSyncChangeKind.REMOVED && remote.kind == SourceSyncChangeKind.REMOVED) {
            return ignore(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = SourceSyncChangeKind.REMOVED,
                reason = SyncRoundReasons.BOTH_DELETED,
                local = local,
                remote = remote,
                tombstone = tombstoneOf(local, key),
            )
        }

        if (local.kind == SourceSyncChangeKind.REMOVED) {
            return approval(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = remote.kind,
                reason = SyncRoundReasons.DELETE_VS_UPDATE,
                local = local,
                remote = remote,
                record = remote.record,
                tombstone = tombstoneOf(local, key),
            )
        }

        if (remote.kind == SourceSyncChangeKind.REMOVED) {
            return approval(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = local.kind,
                reason = SyncRoundReasons.UPDATE_VS_DELETE,
                local = local,
                remote = remote,
                record = local.record,
                tombstone = tombstoneOf(remote, key),
            )
        }

        val localRecord = local.record
        val remoteRecord = remote.record
        if (localRecord == null || remoteRecord == null) {
            // 说是「记录变化」却没带记录 —— 这是畸形摘要不是决定；当冲突挂起，别送进应用桶。
            return approval(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = remote.kind,
                reason = SyncRoundReasons.SAME_REVISION_DIFFERENT_CONTENT,
                local = local,
                remote = remote,
                record = remoteRecord,
            )
        }

        val localHash = SourceSyncContentHash.compute(localRecord)
        val remoteHash = SourceSyncContentHash.compute(remoteRecord)

        if (localHash == remoteHash) {
            if (remote.recordRevision > local.recordRevision) {
                return apply(
                    key = key,
                    direction = SyncMergeDirection.PULL,
                    kind = remote.kind,
                    reason = SyncRoundReasons.REMOTE_NEWER,
                    local = local,
                    remote = remote,
                    record = remoteRecord,
                )
            }
            if (local.recordRevision > remote.recordRevision) {
                return apply(
                    key = key,
                    direction = SyncMergeDirection.PUSH,
                    kind = local.kind,
                    reason = SyncRoundReasons.LOCAL_NEWER,
                    local = local,
                    remote = remote,
                    record = localRecord,
                )
            }
            // 同内容同修订：两侧各自独立收敛。
            return ignore(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = remote.kind,
                reason = SyncRoundReasons.ALREADY_APPLIED,
                local = local,
                remote = remote,
                record = remoteRecord,
            )
        }

        // 内容不同。两侧都动了哪些字段决定要不要人来裁决：同一 fieldVersion 下撞字段是真冲突，
        // 各改各的字段则不是。
        val conflictingFields = local.changedFields
            .filter { it in remote.changedFields }
            .sorted()

        if (conflictingFields.isNotEmpty() && localRecord.fieldVersion == remoteRecord.fieldVersion) {
            return approval(
                key = key,
                direction = SyncMergeDirection.BOTH,
                kind = remote.kind,
                reason = SyncRoundReasons.FIELD_CONFLICT,
                local = local,
                remote = remote,
                record = remoteRecord,
                conflictingFields = conflictingFields,
            )
        }

        if (remote.recordRevision > local.recordRevision) {
            return apply(
                key = key,
                direction = SyncMergeDirection.PULL,
                kind = remote.kind,
                reason = SyncRoundReasons.REMOTE_NEWER,
                local = local,
                remote = remote,
                record = remoteRecord,
            )
        }

        if (local.recordRevision > remote.recordRevision) {
            return apply(
                key = key,
                direction = SyncMergeDirection.PUSH,
                kind = local.kind,
                reason = SyncRoundReasons.LOCAL_NEWER,
                local = local,
                remote = remote,
                record = localRecord,
            )
        }

        return approval(
            key = key,
            direction = SyncMergeDirection.BOTH,
            kind = remote.kind,
            reason = SyncRoundReasons.SAME_REVISION_DIFFERENT_CONTENT,
            local = local,
            remote = remote,
            record = remoteRecord,
        )
    }

    /** 对端的记录为什么在本端写不了；能写返回 null。 */
    private fun describeUnsupported(record: SourceRecord): String? {
        val wire = record.sourceType.wire
        if (record.sourceType.raw.isEmpty() || wire.isEmpty()) return SyncRoundReasons.UNKNOWN_SOURCE_TYPE
        val resolution = AndroidSourceTypeMap.resolve(record.sourceType)
        if (!resolution.isKnown) return SyncRoundReasons.UNKNOWN_SOURCE_TYPE
        if (!resolution.isSupported) return SyncRoundReasons.NOT_SUPPORTED
        val secrets = record.secrets
        if (secrets != null && secrets !is JsonNull) return SyncRoundReasons.SECRETS_REQUIRE_APPROVAL
        return null
    }

    /** 摘要索引：身份比较口径与别处一致（trim + ASCII 大小写不敏感）。 */
    private fun index(digest: SourceSyncDigest): LinkedHashMap<String, SourceSyncDigestItem> {
        val index = LinkedHashMap<String, SourceSyncDigestItem>()
        for (item in digest.items) {
            val key = trimmed(item.syncSourceId) ?: continue
            val existing = findCaseInsensitive(index, key)
            if (existing == null || item.recordRevision > existing.second.recordRevision) {
                if (existing != null) index.remove(existing.first)
                index[key] = item
            }
        }
        return index
    }

    /** 归并用的规范身份：trim + ASCII 小写，与 WireText 其余用途一致。 */
    private fun lowercaseIdentity(value: String): String = WireText.lowercaseAscii(WireText.trim(value))

    private fun lookup(index: Map<String, SourceSyncDigestItem>, key: String): SourceSyncDigestItem? =
        findCaseInsensitive(index, key)?.second

    private fun findCaseInsensitive(
        index: Map<String, SourceSyncDigestItem>,
        key: String,
    ): Pair<String, SourceSyncDigestItem>? {
        for ((storedKey, value) in index) {
            if (WireText.equalsAsciiIgnoreCase(storedKey, key)) return storedKey to value
        }
        return null
    }

    /** 删除条目代表的墓碑；省略墓碑块时按条目的修订号合成一个。 */
    private fun tombstoneOf(item: SourceSyncDigestItem, key: String): SourceTombstone =
        item.tombstone ?: SourceTombstone(syncSourceId = key, recordRevision = item.recordRevision)

    private fun trimmed(value: String?): String? {
        if (value == null || WireText.isBlank(value)) return null
        return WireText.trim(value)
    }

    private fun apply(
        key: String,
        direction: SyncMergeDirection,
        kind: SourceSyncChangeKind,
        reason: String,
        local: SourceSyncDigestItem? = null,
        remote: SourceSyncDigestItem? = null,
        record: SourceRecord? = null,
        tombstone: SourceTombstone? = null,
        conflictingFields: List<String>? = null,
    ): SyncRoundDecision = SyncRoundDecision(
        SyncRoundBucket.APPLY,
        build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval = false),
    )

    private fun approval(
        key: String,
        direction: SyncMergeDirection,
        kind: SourceSyncChangeKind,
        reason: String,
        local: SourceSyncDigestItem? = null,
        remote: SourceSyncDigestItem? = null,
        record: SourceRecord? = null,
        tombstone: SourceTombstone? = null,
        conflictingFields: List<String>? = null,
    ): SyncRoundDecision = SyncRoundDecision(
        SyncRoundBucket.APPROVAL,
        build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval = true),
    )

    private fun ignore(
        key: String,
        direction: SyncMergeDirection,
        kind: SourceSyncChangeKind,
        reason: String,
        local: SourceSyncDigestItem? = null,
        remote: SourceSyncDigestItem? = null,
        record: SourceRecord? = null,
        tombstone: SourceTombstone? = null,
        conflictingFields: List<String>? = null,
    ): SyncRoundDecision = SyncRoundDecision(
        SyncRoundBucket.IGNORE,
        build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval = false),
    )

    private fun build(
        key: String,
        direction: SyncMergeDirection,
        kind: SourceSyncChangeKind,
        reason: String,
        local: SourceSyncDigestItem?,
        remote: SourceSyncDigestItem?,
        record: SourceRecord?,
        tombstone: SourceTombstone?,
        conflictingFields: List<String>?,
        requiresApproval: Boolean,
    ): SyncRoundEntry = SyncRoundEntry(
        syncSourceId = key,
        direction = direction,
        kind = kind,
        reason = reason,
        record = record,
        tombstone = tombstone,
        conflictingFields = conflictingFields ?: emptyList(),
        localRevision = local?.recordRevision ?: 0L,
        remoteRevision = remote?.recordRevision ?: 0L,
        requiresApproval = requiresApproval,
    )
}

/** 内部：决定落在哪个桶（与 Windows SyncRoundBucket 同序）。 */
internal enum class SyncRoundBucket {
    APPLY,
    APPROVAL,
    IGNORE,
}

/** 内部：一个桶 + 一条条目。 */
internal data class SyncRoundDecision(val bucket: SyncRoundBucket, val entry: SyncRoundEntry)
