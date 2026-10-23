package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncFingerprints
import com.mediahub.core.common.sync.SourceSyncLocalIds
import com.mediahub.core.common.sync.SourceSyncPlanner
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.common.sync.SyncSourceTypes
import com.mediahub.core.common.sync.SyncUrlNormalizer
import com.mediahub.model.ServerType
import java.util.UUID

/**
 * Android 端的来源同步可用路径（W6-C）：导入应用 + 导出信封。
 *
 * 这一层把 W6-A 的契约（信封 / 规划器 / 编解码）与 W6-B 的存储（servers 表的同步身份三列）
 * 接起来，是**唯一会写库的同步入口**。四个桶与 Windows CLI plan 的 counts 同口径：
 *
 * | 桶 | 含义 | 是否写库 |
 * | --- | --- | --- |
 * | applied | Create / Update 且无需用户确认 | 写（唯一写库的桶） |
 * | requiresApproval | 冲突、歧义身份、无可用地址的新建、Adopt、墓碑删除，以及**未知类型** | 不写 |
 * | ignored | NoChange / IgnoreStale（含"墓碑指向本地没有的来源"） | 不写 |
 * | unsupported | requiresApproval 的子集：本端不认识或没有对应类型的 wire 值 | 不写 |
 *
 * unsupported 是 requiresApproval 的**子集**而不是第四个互斥桶：Windows CLI 没有单独的
 * unsupported 计数，它把 unknown-source-type 计进 counts.requiresApproval（W6-A 黄金样本
 * 实测 apply=2 / requiresApproval=1 / ignored=1）。这里保留 unsupported 是为了让调用方能把
 * "已保存但暂不支持"单独展示给用户，同时仍能与 CLI 的计数逐项对照。
 *
 * 两条永不退让的规则：
 * - 墓碑**不删**本地来源（[SourceSyncStore] 根本没有删除方法），只进 requiresApproval 或 ignored；
 * - 未知 wire 类型**不落库**，原始串保留在候选里（D3），将来版本可接管。
 *
 * 幂等：第二次导入同一份信封时，来源记录的修订号相等、内容逐字段相等 → 规划器给出 NoChange，
 * 本服务一件也不写。内容同一性由 [SourceSyncFingerprints] 负责（内容摘要不含修订号），
 * 因此"修订号相同但内容不同"会落进 requiresApproval（same-revision-different-content），
 * 而不是被当成已同步偷偷放过。
 */
class AndroidSourceSyncService(
    private val store: SourceSyncStore,
    private val deviceId: String,
    private val snapshotIdFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        require(deviceId.isNotBlank()) { "deviceId 不能为空：导出信封要求稳定设备标识（ClientIdentity.deviceId）" }
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 读入一份 Windows 导出的信封 JSON 并应用可应用的变更。
     *
     * 解析失败不抛异常：返回带 [SourceSyncImportResult.rejectedReason] 的结果，
     * 原因码与 Windows SourceSyncCodec 逐字一致（malformed-json / missing-field:x /
     * unsupported-schema-version:n 等），调用方可直接展示。
     */
    suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult {
        val decoded = SourceSyncCodec.tryDecode(envelopeJson)
        return when (decoded) {
            is SourceSyncCodec.DecodeResult.Failed ->
                SourceSyncImportResult(rejectedReason = decoded.reason, rejectedMessage = decoded.message)
            is SourceSyncCodec.DecodeResult.Ok -> apply(decoded.envelope)
        }
    }

    private suspend fun apply(envelope: SourceSyncEnvelope): SourceSyncImportResult {
        val locals = store.listLocalSources()
        val plan = SourceSyncPlanner.plan(envelope, locals)

        val applied = ArrayList<AppliedSourceChange>()
        val failed = ArrayList<SourceSyncCandidate>()
        val writtenIndexes = HashSet<Int>()
        val failedIndexes = HashSet<Int>()

        for (index in envelope.sources.indices) {
            val candidate = plan.items[index]
            if (!isWritable(candidate)) continue
            val record = envelope.sources[index]

            val serverType = AndroidSourceTypeMap.toServerType(record.sourceType.raw)
            if (serverType == null) {
                // 理论上规划器已经把它拦成 Unsupported；这里再守一次，避免将来改动后静默写坏数据。
                failed.add(candidate.copy(reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE))
                failedIndexes.add(index)
                continue
            }

            val endpoints = normalizedEndpoints(record)
            if (endpoints.isEmpty()) {
                // 没有任何可拨号地址：写入只会得到一条永远连不上的来源。
                failed.add(candidate.copy(reason = SourceSyncReasons.NO_USABLE_ENDPOINT))
                failedIndexes.add(index)
                continue
            }

            val snapshot = snapshotOf(record, serverType, endpoints, candidate.serverId.orEmpty())

            if (candidate.action == SourceSyncAction.CREATE) {
                val serverId = store.createLocalSource(snapshot)
                applied.add(AppliedSourceChange(serverId, candidate, SourceSyncFingerprints.of(record)))
                writtenIndexes.add(index)
                continue
            }

            val serverId = candidate.serverId
            if (serverId == null) {
                failed.add(candidate.copy(reason = SOURCE_VANISHED))
                failedIndexes.add(index)
                continue
            }
            if (!store.updateLocalSource(serverId, snapshot)) {
                // 规划之后、写入之前本地记录被删了：报出来，不要让调用方以为已应用。
                failed.add(candidate.copy(reason = SOURCE_VANISHED))
                failedIndexes.add(index)
                continue
            }
            applied.add(AppliedSourceChange(serverId, candidate, SourceSyncFingerprints.of(record)))
            writtenIndexes.add(index)
        }

        val ignored = ArrayList<SourceSyncCandidate>()
        val pending = ArrayList<SourceSyncCandidate>()
        val unsupported = ArrayList<SourceSyncCandidate>()
        for (index in plan.items.indices) {
            val candidate = plan.items[index]
            when {
                candidate.action == SourceSyncAction.UNSUPPORTED -> unsupported.add(candidate)
                writtenIndexes.contains(index) -> Unit
                failedIndexes.contains(index) -> Unit
                candidate.action == SourceSyncAction.NO_CHANGE ||
                    candidate.action == SourceSyncAction.IGNORE_STALE -> ignored.add(candidate)
                else -> pending.add(candidate)
            }
        }

        return SourceSyncImportResult(
            deviceId = envelope.deviceId,
            snapshotId = envelope.snapshotId,
            envelopeContentHash = SourceSyncFingerprints.content(envelope),
            applied = applied,
            requiresApproval = pending + unsupported,
            ignored = ignored,
            unsupported = unsupported,
            failed = failed,
        )
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 从本机服务器表构建一份信封并编码成 JSON。
     *
     * - deviceId 用构造时传入的稳定设备标识（生产环境取 ClientIdentity.deviceId）；
     * - snapshotId 每次导出都新生成（合同要求唯一），因此同一份数据导出两次字节不同，
     *   内容摘要 [SourceSyncFingerprints.content] 才是"是否同一份内容"的判据；
     * - records 按 syncSourceId 稳定排序（相同 id 时用展示名兜底），两端对比时顺序可预期；
     * - 还没有同步身份的来源会**补号**：按 deviceId + serverId 确定性派生 local-xxxxxxxxxxxxxxxx
     *   并写回存储（与 Windows export 回写 syncSourceId 的行为一致），这样下一次导出的 id 不会漂移；
     * - 没有线路、或既没有 raw 也没有已知类型的来源**跳过**并记在 skippedServerIds 里——
     *   信封里塞一条 url 为空的记录会被读端判 missing-field:url，等于整份信封作废。
     */
    suspend fun export(): SourceSyncExport {
        val snapshots = store.listSourceSnapshots()
        val derived = ArrayList<String>()
        val skipped = ArrayList<String>()
        val records = ArrayList<SourceRecord>()

        for (snapshot in snapshots) {
            val endpoints = snapshot.endpoints
                .filter { it.url.isNotBlank() }
                .map { SourceEndpoint(url = it.url, isPrimary = it.isPrimary, enabled = it.enabled, sortOrder = it.sortOrder) }
            if (endpoints.isEmpty()) {
                skipped.add(snapshot.serverId)
                continue
            }

            val wire = snapshot.sourceTypeRaw?.takeIf { it.isNotBlank() }
                ?: AndroidSourceTypeMap.toWire(snapshot.serverType)
            if (wire == null) {
                skipped.add(snapshot.serverId)
                continue
            }

            var syncSourceId = snapshot.syncSourceId?.takeIf { it.isNotBlank() }
            if (syncSourceId == null) {
                val derivedId = SourceSyncLocalIds.derive(deviceId, snapshot.serverId)
                // 回写失败不影响导出的稳定性：派生是确定性的（deviceId + serverId），下次导出算出同一个 id。
                // 只有当身份真的落库了才报进 derivedIdentityServerIds，避免调用方以为已经持久化。
                if (store.bindSyncIdentity(snapshot.serverId, derivedId, snapshot.recordRevision, snapshot.sourceTypeRaw)) {
                    derived.add(snapshot.serverId)
                }
                syncSourceId = derivedId
            }

            records.add(
                SourceRecord(
                    syncSourceId = syncSourceId,
                    sourceType = SyncSourceTypeValue.parse(wire),
                    displayName = snapshot.name,
                    accountScope = snapshot.accountScope?.takeIf { it.isNotBlank() },
                    endpoints = endpoints,
                    recordRevision = snapshot.recordRevision,
                ),
            )
        }

        val sorted = records.sortedWith(compareBy({ it.syncSourceId }, { it.displayName }))
        val envelope = SourceSyncEnvelope(
            deviceId = deviceId,
            snapshotId = snapshotIdFactory(),
            createdAtEpochMs = clock(),
            capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = declaredSourceTypes(sorted)),
            sources = sorted,
            // 本端没有删除日志表：导出恒为空，绝不猜测"哪些来源被删过"。
            deletions = emptyList(),
        )
        return SourceSyncExport(
            envelope = envelope,
            json = SourceSyncCodec.encode(envelope),
            derivedIdentityServerIds = derived,
            skippedServerIds = skipped,
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun isWritable(candidate: SourceSyncCandidate): Boolean =
        !candidate.requiresUserApproval &&
            (candidate.action == SourceSyncAction.CREATE || candidate.action == SourceSyncAction.UPDATE)

    private fun snapshotOf(
        record: SourceRecord,
        serverType: ServerType,
        endpoints: List<SourceEndpoint>,
        serverId: String,
    ): SourceSyncSourceSnapshot = SourceSyncSourceSnapshot(
        serverId = serverId,
        name = record.displayName.ifBlank { serverType.label },
        serverType = serverType,
        sourceTypeRaw = record.sourceType.raw.takeIf { it.isNotBlank() },
        accountScope = record.accountScope?.trim()?.takeIf { it.isNotEmpty() },
        syncSourceId = record.syncSourceId.trim().takeIf { it.isNotEmpty() },
        recordRevision = record.recordRevision,
        endpoints = endpoints,
        createdAtEpochMs = clock(),
    )

    /** 地址先按 D4 规范化再落库；规范化不出来的端点直接丢弃（写入一条拨不通的线路没有意义）。 */
    private fun normalizedEndpoints(record: SourceRecord): List<SourceEndpoint> {
        val result = ArrayList<SourceEndpoint>(record.endpoints.size)
        for (endpoint in record.endpoints) {
            val normalized = SyncUrlNormalizer.normalize(endpoint.url) ?: continue
            result.add(endpoint.copy(url = normalized))
        }
        // 主端点排最前：存储层会把第一条线路当主线路，顺序反了会让"主"这条语义丢失。
        return result.sortedWith(
            compareByDescending<SourceEndpoint> { it.isPrimary }.thenBy { it.sortOrder },
        )
    }

    private fun declaredSourceTypes(records: List<SourceRecord>): List<String> {
        val present = records.map { it.sourceType.wire }.toSet()
        val known = SyncSourceTypes.ALL.filter { present.contains(it) }
        // 未知 wire 值按记录顺序追加在合同词汇之后（与 Windows CLI 的 abilities 声明同构）。
        val unknown = records.map { it.sourceType.wire }.filter { !SyncSourceTypes.ALL.contains(it) }.distinct()
        return known + unknown
    }

    companion object {
        /**
         * Android 侧新增的 reason 码（合同词汇之外）：规划完成到写入之间本地记录消失（并发删除）。
         * 单独一个码而不是复用 stale-revision，是为了让调用方能区分"内容过期"与"记录没了"。
         */
        const val SOURCE_VANISHED: String = "local-source-vanished"
    }
}

/** 一条真正写进本机的变更。 */
data class AppliedSourceChange(
    val serverId: String,
    val candidate: SourceSyncCandidate,
    /** 远端记录的内容 + 修订号摘要，写入后可用它与本地快照对齐。 */
    val contentHash: String,
)

/**
 * 导入结果。四个桶如类注释所述：unsupported 是 requiresApproval 的子集，
 * 因此 applied + requiresApproval + ignored + failed == 计划条目数。
 */
data class SourceSyncImportResult(
    /** 信封自报的设备标识（回显，便于排查是哪台机器发的）。 */
    val deviceId: String? = null,
    val snapshotId: String? = null,
    /** 整份信封的内容摘要（不含 deviceId / snapshotId / 时间戳），幂等判定与日志用。 */
    val envelopeContentHash: String? = null,
    val applied: List<AppliedSourceChange> = emptyList(),
    val requiresApproval: List<SourceSyncCandidate> = emptyList(),
    val ignored: List<SourceSyncCandidate> = emptyList(),
    val unsupported: List<SourceSyncCandidate> = emptyList(),
    val failed: List<SourceSyncCandidate> = emptyList(),
    /** 非空表示整份信封没被读入（解析或版本校验失败），四个桶必为 0。 */
    val rejectedReason: String? = null,
    val rejectedMessage: String? = null,
) {
    val appliedCount: Int get() = applied.size
    val requiresApprovalCount: Int get() = requiresApproval.size
    val ignoredCount: Int get() = ignored.size
    val unsupportedCount: Int get() = unsupported.size
    val failedCount: Int get() = failed.size

    /** 计划条目总数（与信封 sources + deletions 数量一致，除非被拒绝读入）。 */
    val totalCount: Int get() = applied.size + requiresApproval.size + ignored.size + failed.size

    val isRejected: Boolean get() = !rejectedReason.isNullOrBlank()

    /** 是否有任何数据被写进本机。 */
    val hasWrites: Boolean get() = applied.isNotEmpty()

    /** 是否有需要用户逐条确认的条目（含未知类型）。 */
    val needsApproval: Boolean get() = requiresApproval.isNotEmpty()
}

/** 导出结果。 */
data class SourceSyncExport(
    val envelope: SourceSyncEnvelope,
    val json: String,
    /** 本次导出补了同步身份的服务器 id（对应 Windows export 的"回写 store"）。 */
    val derivedIdentityServerIds: List<String> = emptyList(),
    /** 无法进信封而被跳过的服务器 id（无线路 / 无类型）。 */
    val skippedServerIds: List<String> = emptyList(),
)
