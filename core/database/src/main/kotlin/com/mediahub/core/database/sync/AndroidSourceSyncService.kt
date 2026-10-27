package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncBaseline
import com.mediahub.core.common.sync.SourceSyncBaselineSaveResult
import com.mediahub.core.common.sync.SourceSyncBaselineStoreReasons
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncDigest
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncEndpointPolicy
import com.mediahub.core.common.sync.SourceSyncEndpointSummary
import com.mediahub.core.common.sync.SourceSyncFingerprints
import com.mediahub.core.common.sync.SourceSyncLocalIds
import com.mediahub.core.common.sync.SourceSyncPlanner
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SourceSyncSecrets
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncMergeDirection
import com.mediahub.core.common.sync.SyncRoundMerger
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.common.sync.SyncRoundResult
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.common.sync.SyncSourceTypes
import com.mediahub.core.common.sync.SyncUrlNormalizer
import com.mediahub.model.ServerType
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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
 * 三条永不退让的规则：
 * - 墓碑**不删**本地来源（[SourceSyncStore] 根本没有删除方法），只进 requiresApproval 或 ignored；
 * - 未知 wire 类型**不落库**，原始串保留在候选里（D3），将来版本可接管；
 * - 带凭据块的记录**不落库**（W6-74，理由 secrets-require-approval）：本端用不了对端密文，
 *   静默写一条"没有凭据的来源"或静默剥掉凭据块，都是在替用户做他没有做的决定。
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
    private val baselineStore: ISourceSyncBaselineStore? = null,
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
        // 信封内重复的 syncSourceId 会让规划器给出两条 CREATE：第一条写进库，第二条撞上
        // servers 表 sync_source_id 的唯一索引 → SQLiteConstraintException 整封逃逸，且已经写了一条。
        // 身份必须在信封内部先唯一，所以整封拒绝（不部分写入），返回稳定码而不是异常。
        val duplicated = duplicateSyncSourceIds(envelope)
        if (duplicated.isNotEmpty()) {
            return SourceSyncImportResult(
                deviceId = envelope.deviceId,
                snapshotId = envelope.snapshotId,
                envelopeContentHash = SourceSyncFingerprints.content(envelope),
                rejectedReason = DUPLICATE_SYNC_SOURCE_ID,
                rejectedMessage = "The envelope repeats the same syncSourceId more than once (" +
                    duplicated.joinToString(", ") + "); nothing was imported.",
            )
        }

        val locals = store.listLocalSources()
        val plan = SourceSyncPlanner.plan(envelope, locals)

        // W6-74：凭据闸门。对端记录只要带非 null 的 secrets 块，就一律不写、转人工确认，理由码
        // 与 Windows SourceSyncImportRound.Preview 逐字一致（secrets-require-approval）。
        // 覆盖是无条件的：Windows 也是不看规划器原来给什么理由，直接盖章要求确认。
        val withheldIndexes = secretsWithheldIndexes(envelope)
        val gated = gateSecrets(plan.items, withheldIndexes)

        // W6-74：读上一轮基线。只用于汇报"上次跟这台设备收敛到第几条"，不参与规划——
        // 两端都不拿基线改判，免得出现"基线坏了就换一种合并结果"的隐式分支。
        val peerBaseline = loadPeerBaseline(envelope.deviceId)

        val applied = ArrayList<AppliedSourceChange>()
        val failed = ArrayList<SourceSyncCandidate>()
        // W6-72：只有真正写进库的条目才进对端基线（Windows W6-10 同口径）。
        val writtenRecords = ArrayList<SourceRecord>()
        val writtenIndexes = HashSet<Int>()
        val failedIndexes = HashSet<Int>()

        for (index in envelope.sources.indices) {
            val candidate = gated[index]
            if (!isWritable(candidate)) continue
            val record = envelope.sources[index]

            // 单条写入失败（磁盘满、唯一索引、权限）不得把异常抛出 importEnvelope：
            // 那会让调用方既拿不到"哪一条失败"，也看不到已经写进去的那些。
            try {
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
                    writtenRecords.add(record)
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
                writtenRecords.add(record)
                writtenIndexes.add(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // 存储层拒绝（唯一索引 / IO / 权限）统一降级成 failed 桶，四桶不变式仍然成立。
                failed.add(candidate.copy(reason = WRITE_FAILED + ":" + (failure::class.simpleName ?: "Exception")))
                failedIndexes.add(index)
            }
        }

        val ignored = ArrayList<SourceSyncCandidate>()
        val pending = ArrayList<SourceSyncCandidate>()
        val unsupported = ArrayList<SourceSyncCandidate>()
        for (index in gated.indices) {
            val candidate = gated[index]
            when {
                candidate.action == SourceSyncAction.UNSUPPORTED -> unsupported.add(candidate)
                writtenIndexes.contains(index) -> Unit
                failedIndexes.contains(index) -> Unit
                // W6-74：带凭据的条目不论规划器怎么判（连 NoChange / IgnoreStale 也算）都要人看过，
                // 与 Windows ApplyAsync 的 approval gate 一致——那里也是先看 RequiresApproval。
                withheldIndexes.contains(index) -> pending.add(candidate)
                candidate.action == SourceSyncAction.NO_CHANGE ||
                    candidate.action == SourceSyncAction.IGNORE_STALE -> ignored.add(candidate)
                else -> pending.add(candidate)
            }
        }

        val baseline = recordBaseline(envelope, writtenRecords)
        return SourceSyncImportResult(
            deviceId = envelope.deviceId,
            snapshotId = envelope.snapshotId,
            envelopeContentHash = SourceSyncFingerprints.content(envelope),
            applied = applied,
            requiresApproval = pending + unsupported,
            ignored = ignored,
            unsupported = unsupported,
            failed = failed,
            endpointSummary = SourceSyncEndpointPolicy.summarize(envelope),
            baselineSaved = baseline?.saved == true,
            baselineRejectedSyncSourceIds = baseline?.rejectedSyncSourceIds ?: emptyList(),
            baselineRejectionReason = baseline?.reason,
            baselineKnown = peerBaseline?.isEmpty == false,
            baselineEntryCount = peerBaseline?.entries?.size ?: 0,
            secretsWithheldSyncSourceIds = withheldIndexes.sorted().map { envelope.sources[it].syncSourceId.trim() },
        )
    }

    /**
     * W6-72：把本轮真正写入的条目并进对端基线（合并不是替换，见 [ISourceSyncBaselineStore.save]）。
     *
     * - 没有基线存储（老调用方 / JVM 单测）时不写，返回 null；
     * - 一件都没写时不碰磁盘：空基线合并只会产生一次无意义的 Unchanged；
     * - 存储层抛异常（介质不可写）折成稳定码 baseline-write-failed，绝不让导入本身失败。
     */
    private fun recordBaseline(
        envelope: SourceSyncEnvelope,
        writtenRecords: List<SourceRecord>,
    ): SourceSyncBaselineSaveResult? {
        val store = baselineStore ?: return null
        if (writtenRecords.isEmpty()) return null
        val peerDeviceId = envelope.deviceId.takeIf { it.isNotBlank() } ?: return null
        return try {
            store.save(
                peerDeviceId,
                SourceSyncBaseline.fromRecords(
                    records = writtenRecords,
                    deviceId = peerDeviceId,
                    snapshotId = envelope.snapshotId,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            SourceSyncBaselineSaveResult(reason = SourceSyncBaselineStoreReasons.WRITE_FAILED)
        }
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
        val local = buildLocalEnvelope()
        return SourceSyncExport(
            envelope = local.envelope,
            json = SourceSyncCodec.encode(local.envelope),
            derivedIdentityServerIds = local.derivedIdentityServerIds,
            skippedServerIds = local.skippedServerIds,
        )
    }

    /**
     * W6-76：把「本机服务器表 → 信封」这段构造成独立的一步，导出与轮次预演共用同一份记录集。
     *
     * 分成两处实现的后果是「补号 / 跳过无线路」的判据会慢慢分叉：预演说能推、导出却推不出去。
     */
    private suspend fun buildLocalEnvelope(): LocalEnvelopeBuild {
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
        return LocalEnvelopeBuild(
            envelope = envelope,
            derivedIdentityServerIds = derived,
            skippedServerIds = skipped,
        )
    }

    // ---------------------------------------------------------------- 轮次预演

    /**
     * W6-76：一轮双向合并的**只读预演**（对齐 Windows `SourceSyncSession.PlanRound`）。
     *
     * 它回答「这一轮到底要搬什么」，而不是「已经搬了什么」：本机信封与对端信封各自相对**同一份**
     * 基线算成摘要，再交给 [SyncRoundMerger] 分成 可执行 / 需确认 / 忽略 三个桶。
     *
     * 四条边界：
     * - **不写来源**：只读 [SourceSyncStore.listSourceSnapshots]，不调用 createLocalSource /
     *   updateLocalSource —— 「预演」若会落库，用户在界面上看到的就不再是计划；
     * - **会补身份**：缺 syncSourceId 的来源按 deviceId + serverId 确定性派生（与导出、与 Windows
     *   `SourceSyncLocalBridge.BuildEnvelope` 同口径）；不补号的话这些来源在摘要里只会变成
     *   missing-sync-source-id 问题项，永远推不出去，预演会误报「没有工作」；
     * - **基线只读不写**：基线由导入轮在真正写入之后推进，预演绝不推进 —— 否则「看一眼」就让下一轮
     *   的 already-applied 提前成立，等于凭空吞掉一条待同步记录；
     * - **解析失败不抛**：返回带 [SourceSyncRoundPreview.rejectedReason] 的空预演，调用方按稳定码分支。
     */
    suspend fun planRound(envelopeJson: String): SourceSyncRoundPreview {
        val decoded = SourceSyncCodec.tryDecode(envelopeJson)
        if (decoded is SourceSyncCodec.DecodeResult.Failed) {
            return SourceSyncRoundPreview(
                rejectedReason = decoded.reason,
                rejectedMessage = decoded.message,
            )
        }
        val envelope = (decoded as SourceSyncCodec.DecodeResult.Ok).envelope

        // 基线为 null 时用 EMPTY 而不是空实现：摘要那边把「基线 null」当首次接触（全量），
        // 把「基线空」也当全量 —— 两者结果一致，但传 null 会绕过 merge 的 known == null 判据。
        val peerBaseline = loadPeerBaseline(envelope.deviceId)
        val baseline = peerBaseline ?: SourceSyncBaseline.EMPTY
        val local = buildLocalEnvelope()

        val round = SyncRoundMerger.merge(
            local = SourceSyncDigest.fromEnvelope(local.envelope, baseline),
            remote = SourceSyncDigest.fromEnvelope(envelope, baseline),
            baseline = baseline,
        )

        return SourceSyncRoundPreview(
            peerDeviceId = envelope.deviceId,
            baselineKnown = peerBaseline?.isEmpty == false,
            baselineEntryCount = peerBaseline?.entries?.size ?: 0,
            derivedIdentityServerIds = local.derivedIdentityServerIds,
            skippedServerIds = local.skippedServerIds,
            round = round,
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * W6-74：信封里带真凭据的来源下标（规划器把来源排在墓碑之前，所以下标与 items 前 N 项一一对应）。
     *
     * 判据只有一条，统一在 [SourceSyncSecrets.carriesBlock]：secrets 存在且不是 JSON null。
     */
    private fun secretsWithheldIndexes(envelope: SourceSyncEnvelope): Set<Int> {
        val indexes = HashSet<Int>()
        for (index in envelope.sources.indices) {
            if (SourceSyncSecrets.carriesBlock(envelope.sources[index])) indexes.add(index)
        }
        return indexes
    }

    /**
     * W6-74：把带凭据的候选改判成"必须人工确认"。
     *
     * 只换理由与 requiresUserApproval，动作/修订号/候选 serverId 全部保留：审批界面要能显示
     * "这是对哪一条的哪个动作要确认"，改动作会让审批结果对不上用户看到的描述。
     */
    private fun gateSecrets(
        items: List<SourceSyncCandidate>,
        withheldIndexes: Set<Int>,
    ): List<SourceSyncCandidate> {
        if (items.isEmpty() || withheldIndexes.isEmpty()) return items
        val gated = ArrayList<SourceSyncCandidate>(items.size)
        for (index in items.indices) {
            val candidate = items[index]
            gated.add(
                if (withheldIndexes.contains(index)) {
                    candidate.copy(
                        reason = SyncRoundReasons.SECRETS_REQUIRE_APPROVAL,
                        requiresUserApproval = true,
                    )
                } else {
                    candidate
                },
            )
        }
        return gated
    }

    /**
     * W6-74：读对端基线，读不出来就当不知道。
     *
     * [ISourceSyncBaselineStore.load] 本身约定不抛异常；这里再兜一层是防将来换成会抛的实现
     * （例如读到半截文件）——读基线失败不该把整次导入变成失败。
     */
    private fun loadPeerBaseline(peerDeviceId: String): SourceSyncBaseline? {
        val store = baselineStore ?: return null
        if (peerDeviceId.isBlank()) return null
        return try {
            store.load(peerDeviceId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
    }

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

    /**
     * 信封内部重复出现的 syncSourceId（trim 后忽略大小写）。
     *
     * 大小写也算重复：servers 表的唯一索引是大小写敏感的，但两端身份比较（Windows 的
     * SourceSyncApprovalGate、本端 `findBySyncSourceId`）都把 id 当"trim + 忽略大小写"的身份，
     * 因此仅大小写不同的两条记录本就指向同一个来源——放过去只会在库里留下两个身份相同的来源。
     * 返回的是第一次出现的拼写，便于调用方在错误文案里展示。
     */
    private fun duplicateSyncSourceIds(envelope: SourceSyncEnvelope): List<String> {
        val counts = LinkedHashMap<String, Int>()
        val firstSpelling = LinkedHashMap<String, String>()
        for (record in envelope.sources) {
            val id = record.syncSourceId.trim()
            if (id.isEmpty()) continue
            val key = id.lowercase()
            counts[key] = (counts[key] ?: 0) + 1
            firstSpelling.putIfAbsent(key, id)
        }
        return counts.filterValues { it > 1 }.keys.mapNotNull { firstSpelling[it] }
    }

    companion object {
        /**
         * Android 侧新增的 reason 码（合同词汇之外）：规划完成到写入之间本地记录消失（并发删除）。
         * 单独一个码而不是复用 stale-revision，是为了让调用方能区分"内容过期"与"记录没了"。
         */
        const val SOURCE_VANISHED: String = "local-source-vanished"

        /**
         * 同一份信封里出现两条相同 syncSourceId：整封拒绝（不部分写入）。
         * 与 [SOURCE_VANISHED] 一样属于 Android 侧、不参与线上格式的稳定码。
         */
        const val DUPLICATE_SYNC_SOURCE_ID: String = "duplicate-sync-source-id"

        /**
         * 逐条写入抛异常时的稳定码前缀，实际值为 `local-source-write-failed:<异常类名>`。
         * 异常类型进码里是为了让排查能区分"唯一索引冲突"与"磁盘/权限"——
         * 两者对用户的处置完全不同，但都不该以异常形式冒给调用方。
         */
        const val WRITE_FAILED: String = "local-source-write-failed"
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
    /**
     * W6-72：信封里被「主端点优先」策略判为不导入的次要端点汇总（对齐 Windows SourceSyncEndpointPolicy）。
     *
     * Android 本端**不丢**其它端点（本机线路全部保留，见 [normalizedEndpoints]），
     * 因此这份汇总是给用户对照用的信息（"对面还会看到哪些线路没同步"），不代表本端删了线路。
     */
    val endpointSummary: SourceSyncEndpointSummary? = null,
    /** W6-72：本轮是否把真正写入的条目并进了对端基线。 */
    val baselineSaved: Boolean = false,
    /** W6-72：基线拒绝的条目（合并不让条目倒退）。 */
    val baselineRejectedSyncSourceIds: List<String> = emptyList(),
    /** W6-72：基线被拒/写失败时的稳定码。 */
    val baselineRejectionReason: String? = null,
    /**
     * W6-74：本轮读到的对端基线是否非空（"以前跟这台设备收敛过"）。
     *
     * 读文件失败、没有基线存储、或对端从没同步过，一律 false；导入本身不受影响。
     */
    val baselineKnown: Boolean = false,
    /** W6-74：对端基线里已有的来源条数（[baselineKnown] 为 false 时恒为 0）。 */
    val baselineEntryCount: Int = 0,
    /** W6-74：因携带凭据块被扣下、转人工确认的 syncSourceId（按信封顺序，绝不带凭据内容）。 */
    val secretsWithheldSyncSourceIds: List<String> = emptyList(),
    /** 非空表示整份信封没被读入（解析或版本校验失败），四个桶必为 0。 */
    val rejectedReason: String? = null,
    val rejectedMessage: String? = null,
) {
    val appliedCount: Int get() = applied.size
    val requiresApprovalCount: Int get() = requiresApproval.size
    val ignoredCount: Int get() = ignored.size
    val unsupportedCount: Int get() = unsupported.size
    val failedCount: Int get() = failed.size
    /** W6-74：被凭据闸门扣下、等用户确认的条数。 */
    val secretsWithheldCount: Int get() = secretsWithheldSyncSourceIds.size

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

/** [buildLocalEnvelope] 的产出：信封本体 + 这次顺手补了哪些号、跳过了谁。 */
private data class LocalEnvelopeBuild(
    val envelope: SourceSyncEnvelope,
    val derivedIdentityServerIds: List<String>,
    val skippedServerIds: List<String>,
)

/**
 * W6-76：一轮双向合并的只读预演结果。
 *
 * [round] 是决定本身；其余字段是「这份计划建立在什么之上」——基线知道多少、本机补了几个身份、
 * 有几条来源因为没线路/没类型被跳过。三项计数都只描述 [round.apply]，需要人工确认的另算。
 */
data class SourceSyncRoundPreview(
    /** 对端设备标识（解析失败时为空串）。 */
    val peerDeviceId: String = "",
    /** 对端基线是否非空（"以前跟这台设备收敛过"）。没有基线存储、读失败、从没同步过一律 false。 */
    val baselineKnown: Boolean = false,
    /** 对端基线里已有的来源条数（[baselineKnown] 为 false 时恒为 0）。 */
    val baselineEntryCount: Int = 0,
    /** 本次预演顺手补号的服务器 id（[SourceSyncStore.bindSyncIdentity] 成功才算）。 */
    val derivedIdentityServerIds: List<String> = emptyList(),
    /** 无法进信封的服务器 id（无线路 / 无类型），与导出同口径。 */
    val skippedServerIds: List<String> = emptyList(),
    /** 非空表示信封没被读入，此时 [round] 必为空。 */
    val rejectedReason: String? = null,
    val rejectedMessage: String? = null,
    val round: SyncRoundResult = SyncRoundResult.EMPTY,
) {
    val isRejected: Boolean get() = !rejectedReason.isNullOrBlank()

    /** 可以不经确认直接执行的条目数。 */
    val applyCount: Int get() = round.apply.size

    /** 必须等人回答的条目数（含未知类型、删本地、字段冲突）。 */
    val approvalCount: Int get() = round.requiresApproval.size

    /** 不需要任何动作的条目数（已同步、对端过旧、墓碑无本地对应…）。 */
    val ignoredCount: Int get() = round.ignored.size

    /** 本次要**推给对端**的可执行条目数（只数 apply 里的 PUSH）。 */
    val pushCount: Int get() = round.apply.count { it.direction == SyncMergeDirection.PUSH }

    /** 本次要从**对端拉下来**写入本机的可执行条目数（只数 apply 里的 PULL）。 */
    val pullCount: Int get() = round.apply.count { it.direction == SyncMergeDirection.PULL }

    /** 有没有可以不经确认直接做的工作；false 表示这一轮是空的（两端已收敛）。 */
    val hasWork: Boolean get() = round.hasWork

    val isEmpty: Boolean get() = round.isEmpty
}
