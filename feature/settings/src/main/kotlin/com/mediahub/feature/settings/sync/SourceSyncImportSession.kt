package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.database.repository.SyncSourceStore
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.database.sync.SourceSyncImportResult
import com.mediahub.core.database.sync.SourceSyncPreview
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

/** 应用端口：默认实现是本机 AndroidSourceSyncService（唯一写库入口）。 */
fun interface SourceSyncApplyPort {
    suspend fun apply(envelopeJson: String): SourceSyncImportResult
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
 * - 失败关闭：**没有点"允许"的可允许条目会被剔除**，不会被静默导入；
 * - 不可允许条目留在信封里：它们本来就写不进去，剔除反而会让结果页少报一条冲突；
 * - 整份信封被拒（reason 码）或一条都不允许 → 状态回到可重试位置并给出 blockedReason。
 */
class SourceSyncImportSession(
    private val previewPort: SourceSyncPreviewPort,
    private val applyPort: SourceSyncApplyPort,
    private val filterEnvelope: (String, Set<String>) -> String = SourceSyncImportLogic::filterEnvelope,
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

    /** 应用：剔除未允许的条目后交给写库服务。 */
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
        val filtered = filterEnvelope(json, preview.skippedKeys)
        val result = runCatching { applyPort.apply(filtered) }
        result.fold(
            onSuccess = { applied ->
                if (applied.isRejected) {
                    block(applied.rejectedReason ?: SourceSyncImportCopy.APPLY_FAILED, applied.rejectedMessage)
                } else {
                    _state.update {
                        it.copy(
                            status = SourceSyncImportStatus.APPLIED,
                            isBusy = false,
                            applied = summaryOf(applied),
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

    private fun summaryOf(result: SourceSyncImportResult): SourceSyncApplySummary =
        SourceSyncApplySummary(
            appliedCount = result.appliedCount,
            requiresApprovalCount = result.requiresApprovalCount,
            ignoredCount = result.ignoredCount,
            unsupportedCount = result.unsupportedCount,
            failedCount = result.failedCount,
            appliedTitles = result.applied.map { change -> titleOf(change.candidate) },
            appliedSources = result.applied.map { change ->
                appliedSourceOf(change.serverId, change.candidate)
            },
        )

    /** 一条落库来源：结果页据此渲染逐源「补录并验证」（W6-H）。 */
    private fun appliedSourceOf(
        serverId: String,
        candidate: SourceSyncCandidate,
    ): SourceSyncAppliedSource = SourceSyncAppliedSource(
        serverId = serverId,
        title = titleOf(candidate),
        sourceTypeRaw = candidate.sourceType?.trim()?.takeIf { it.isNotEmpty() },
        syncSourceId = candidate.syncSourceId?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun titleOf(candidate: SourceSyncCandidate): String =
        candidate.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: candidate.sourceType?.trim()
            ?: candidate.syncSourceId.orEmpty()

    companion object {
        /** 生产接线：只读预览 + 唯一写库入口，两个服务都用本机仓库实现。 */
        fun production(
            localSources: SyncSourceStore,
            sourceSyncStore: SourceSyncStore,
            deviceId: String,
        ): SourceSyncImportSession {
            val previewService = SourceSyncPreviewService(localSources)
            val applyService = AndroidSourceSyncService(sourceSyncStore, deviceId)
            return SourceSyncImportSession(
                previewPort = SourceSyncPreviewPort { json -> previewService.preview(json) },
                applyPort = SourceSyncApplyPort { json -> applyService.importEnvelope(json) },
            )
        }
    }
}

/** 便于测试直接拼一个 plan（无副作用）。 */
internal fun planOf(candidates: List<SourceSyncCandidate>): SourceSyncPlan = SourceSyncPlan(candidates)
