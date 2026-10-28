package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.database.repository.SyncSourceStore
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.database.sync.SourceSyncApplyReport
import com.mediahub.core.database.sync.SourceSyncPreview
import com.mediahub.core.database.sync.SourceSyncSourceRemover
import com.mediahub.core.database.sync.SourceSyncPreviewService
import com.mediahub.core.database.sync.SourceSyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 预览端口：默认实现是本机 SourceSyncPreviewService（零写入）。 */
fun interface SourceSyncPreviewPort {
    suspend fun preview(envelopeJson: String): SourceSyncPreview
}

/**
 * 应用端口：默认实现是本机 AndroidSourceSyncService 的 [AndroidSourceSyncService.applyRound]（唯一写库入口）。
 *
 * 从 W6-81 起，端口收的是「整份信封 + 两张名单」而不是一份被过滤过的信封：
 * 剔除式过滤只能表达「不写」，没法让被扣住的凭据、冲突与删除在回执里留下痕迹；
 * 名单则逐条交给闸门，回执里因此有写入 / 跳过 / 扣住 / 拒写四个真实计数。
 */
fun interface SourceSyncApplyPort {
    suspend fun apply(
        envelopeJson: String,
        allowedKeys: Set<String>,
        skippedKeys: Set<String>,
    ): SourceSyncApplyReport
}

/**
 * 导入页的状态机（W6-F）。
 *
 * 这里刻意不继承 ViewModel、不碰 Android Context、不依赖 Compose：整条"选择 → 预览 → 逐条决策 →
 * 应用"的流程因此可以在 JVM 单测里跑（本类自己的测试就是这么做证据的）。
 * SourceSyncImportViewModel 只是把它接到生命周期上的一层薄壳。
 *
 * 语义约定（与 SourceSyncImportLogic 一起被测试钉住）：
 * - 预览零写入：只走 SourceSyncPreviewPort，它是只读服务；
 * - 失败关闭：没点"允许"的可允许条目进跳过名单，**不会被静默导入**；
 * - 整份信封原样交给服务：名单只表达裁决，不改写信封 —— 信封少一条，回执就没法说明为什么少；
 * - 不可允许条目不在任何名单里：它们由闸门无条件扣住，结果页按扣住数如实展示；
 * - 整份信封被拒（reason 码）或一条都不允许 → 状态回到可重试位置并给出 blockedReason。
 */
class SourceSyncImportSession(
    private val previewPort: SourceSyncPreviewPort,
    private val applyPort: SourceSyncApplyPort,
) {
    private val _state = MutableStateFlow(SourceSyncImportState())
    val state: StateFlow<SourceSyncImportState> = _state.asStateFlow()

    /** 选好一份信封（还没预览）。 */
    fun loadEnvelope(envelopeJson: String, fileName: String? = null) {
        _state.value = SourceSyncImportState(
            status = SourceSyncImportStatus.READY_TO_PREVIEW,
            fileName = fileName,
            envelopeJson = envelopeJson,
        )
    }

    /**
     * 选到的是加密归档、但口令还没给（或给错了）：停在"要口令"的位置。
     *
     * 刻意不把密文写进 envelopeJson：状态里躺着二进制密文既没有用，也会让 canPreview 误判为可预览。
     */
    fun requirePassphrase(reason: String, message: String?, fileName: String? = null) {
        _state.value = SourceSyncImportState(
            status = SourceSyncImportStatus.BLOCKED,
            fileName = fileName,
            blockedReason = reason,
            blockedMessage = message,
        )
    }

    /** 预演：把信封交给只读的规划服务，得到四桶。 */
    suspend fun preview() {
        val json = _state.value.envelopeJson
        if (json.isNullOrBlank()) {
            block(SourceSyncImportCopy.NO_ENVELOPE, null)
            return
        }
        _state.update { it.copy(status = SourceSyncImportStatus.READY_TO_PREVIEW, isBusy = true) }
        val result = runCatching { previewPort.preview(json) }
        result.fold(
            onSuccess = { preview ->
                when (preview) {
                    is SourceSyncPreview.Rejected -> block(preview.reason, preview.message)
                    is SourceSyncPreview.Ready -> _state.update {
                        it.copy(
                            status = SourceSyncImportStatus.PREVIEWED,
                            isBusy = false,
                            preview = viewOf(preview),
                            applied = null,
                            blockedReason = null,
                            blockedMessage = null,
                        )
                    }
                }
            },
            onFailure = { error -> block(SourceSyncImportCopy.PREVIEW_FAILED, error.message) },
        )
    }

    /** 逐条决策；非可允许条目会被忽略（控件已禁用，这里再守一层）。 */
    fun decide(key: String, decision: SourceSyncEntryDecision) {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.status != SourceSyncImportStatus.PREVIEWED) return
        if (key !in preview.allowableKeys) return
        _state.value = current.copy(preview = preview.withDecision(key, decision))
    }

    /** 应用：把整份信封与两张裁决名单一起交给写库服务。 */
    suspend fun apply() {
        val current = _state.value
        val preview = current.preview
        val json = current.envelopeJson
        if (preview == null || json.isNullOrBlank()) {
            block(SourceSyncImportCopy.NO_PREVIEW, null)
            return
        }
        if (!preview.canApply) {
            block(SourceSyncImportCopy.NOTHING_ALLOWED, null)
            return
        }
        _state.update {
            it.copy(
                status = SourceSyncImportStatus.APPLYING,
                isBusy = true,
                blockedReason = null,
                blockedMessage = null,
            )
        }
        val result = runCatching { applyPort.apply(json, preview.allowedKeys, preview.skippedKeys) }
        result.fold(
            onSuccess = { applied ->
                if (applied.isRejected) {
                    block(applied.rejectedReason ?: SourceSyncImportCopy.APPLY_FAILED, applied.rejectedMessage)
                } else {
                    _state.update {
                        it.copy(
                            status = SourceSyncImportStatus.APPLIED,
                            isBusy = false,
                            applied = summaryOf(applied, preview),
                            blockedReason = null,
                            blockedMessage = null,
                        )
                    }
                }
            },
            onFailure = { error -> block(SourceSyncImportCopy.APPLY_FAILED, error.message) },
        )
    }

    /** 回到"选文件"位置（重新选一份信封时用）。 */
    fun reset() {
        _state.value = SourceSyncImportState()
    }

    private fun block(reason: String, message: String?) {
        _state.update { state ->
            state.copy(
                status = SourceSyncImportStatus.BLOCKED,
                isBusy = false,
                blockedReason = reason,
                blockedMessage = message,
            )
        }
    }

    private fun viewOf(preview: SourceSyncPreview.Ready): SourceSyncImportPreviewView =
        SourceSyncImportLogic.previewView(
            deviceId = preview.deviceId,
            snapshotId = preview.snapshotId,
            plan = preview.plan,
        )

    /**
     * 回执 → 结果页摘要。
     *
     * 计数来源分两处，这是有意的：写入 / 跳过 / 扣住 / 拒写是**这一轮真实发生了什么**（服务回执），
     * 需确认 / 已忽略 / 暂不支持是**预览里本来就有的桶**（规划结果）。把后者也从回执里数，
     * 会让「需确认 3 条」变成 0 —— 那三条压根没进闸门，回执里当然没有它们。
     *
     * appliedCount 只数**写入**：删除也是 applied（对端删了就得连凭据一起移除），但那不是「写入」，
     * 混在一起会让结果页说「已写入 3 条」而实际只写了 2 条、删了 1 条。
     */
    private fun summaryOf(
        report: SourceSyncApplyReport,
        preview: SourceSyncImportPreviewView,
    ): SourceSyncApplySummary {
        val sources = writtenSourcesOf(report, preview)
        return SourceSyncApplySummary(
            appliedCount = report.applied - report.removedServerIds.size,
            requiresApprovalCount = preview.countIn(SourceSyncImportBucket.REQUIRES_APPROVAL),
            ignoredCount = preview.countIn(SourceSyncImportBucket.IGNORED),
            unsupportedCount = preview.countIn(SourceSyncImportBucket.UNSUPPORTED),
            failedCount = report.refused,
            skippedCount = report.skipped,
            withheldCount = report.withheld,
            removedCount = report.removedServerIds.size,
            blockingReasons = report.blockingReasons,
            appliedTitles = sources.map { it.title },
            appliedSources = sources,
            notImportedEndpointCount = report.endpointSummary?.notImportedCount ?: 0,
            recordsWithAlternativeEndpoints = report.endpointSummary?.recordsWithAlternatives ?: 0,
            baselineSaved = report.baselineUpdated,
            baselineRejectedCount = report.baselineRejectedCount,
        )
    }

    /**
     * 每条真正写进去的来源：回执给身份 + serverId，预览给标题与 wire 类型。
     *
     * 两边对不上时（不该发生，但回执是外部输入的一部分）退化成只用身份，
     * 而不是丢掉这条 —— 少报一条会让「补录并验证」永久缺一个来源。
     */
    private fun writtenSourcesOf(
        report: SourceSyncApplyReport,
        preview: SourceSyncImportPreviewView,
    ): List<SourceSyncAppliedSource> {
        val byKey = preview.entries.associateBy { SourceSyncImportLogic.normalizeKey(it.key) }
        return report.wrote.map { written ->
            val entry = byKey[SourceSyncImportLogic.normalizeKey(written.syncSourceId)]
            SourceSyncAppliedSource(
                serverId = written.serverId,
                title = entry?.title ?: written.syncSourceId,
                sourceTypeRaw = entry?.candidate?.sourceType?.trim()?.takeIf { it.isNotEmpty() },
                syncSourceId = written.syncSourceId,
            )
        }
    }


    companion object {
        /**
         * 生产接线：只读预览 + 唯一写库入口，两个服务都用本机仓库实现。
         *
         * [baselineStore] 是 W6-72 的对端基线（缺省 null：不记录，行为与 W6-71 完全一致）。
         * [sourceRemover] 是 W6-79 的跨端删除能力（缺省 null：删除类条目只能拒绝并上报，行为与 W6-78 一致）。
         */
        fun production(
            localSources: SyncSourceStore,
            sourceSyncStore: SourceSyncStore,
            deviceId: String,
            baselineStore: ISourceSyncBaselineStore? = null,
            sourceRemover: SourceSyncSourceRemover? = null,
        ): SourceSyncImportSession {
            val previewService = SourceSyncPreviewService(localSources)
            val applyService = AndroidSourceSyncService(
                store = sourceSyncStore,
                deviceId = deviceId,
                baselineStore = baselineStore,
                sourceRemover = sourceRemover,
            )
            return SourceSyncImportSession(
                previewPort = SourceSyncPreviewPort { json -> previewService.preview(json) },
                applyPort = SourceSyncApplyPort { json, allowed, skipped ->
                    applyService.applyRound(
                        envelopeJson = json,
                        allowedSyncSourceIds = allowed.toList(),
                        skippedSyncSourceIds = skipped.toList(),
                    )
                },
            )
        }
    }
}

/** 便于测试直接拼一个 plan（无副作用）。 */
internal fun planOf(candidates: List<SourceSyncCandidate>): SourceSyncPlan = SourceSyncPlan(candidates)
