package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncPlanner
import com.mediahub.core.common.sync.SourceSyncSummary
import com.mediahub.core.database.repository.SyncSourceStore

/**
 * 三桶计数，与 Windows CLI "plan --json" 的 counts 同义（可做跨端对照）：
 * apply = Create + Update，requiresApproval = 需要用户确认的条数，ignored = NoChange + IgnoreStale。
 */
data class SourceSyncBuckets(
    val apply: Int,
    val requiresApproval: Int,
    val ignored: Int,
)

/** 一次预演的结果：要么信封读不进来（Rejected），要么给出可展示的计划（Ready）。 */
sealed interface SourceSyncPreview {

    /** 信封本身不合法（缺字段/版本过高/不是 JSON）——reason 直接用契约层的原因码。 */
    data class Rejected(val reason: String, val message: String?) : SourceSyncPreview

    data class Ready(
        val plan: SourceSyncPlan,
        val summary: SourceSyncSummary,
        val deviceId: String,
        val snapshotId: String,
    ) : SourceSyncPreview {
        /**
         * 三桶。requiresApproval 与 Windows CLI plan 的 counts.requiresApproval 同口径：
         * 规划器标了 requiresUserApproval 的条目，加上"本端不支持"的条目 —— Windows 也把
         * unknown-source-type 放进这一桶（用户至少要看到它不会被同步），照抄以免两端计数对不上。
         */
        val buckets: SourceSyncBuckets
            get() = SourceSyncBuckets(
                apply = summary.apply,
                requiresApproval = needsApproval.size,
                ignored = summary.ignored,
            )

        /** 本机会新建/更新的条目（仍需用户确认后才允许落库）。 */
        val toApply: List<SourceSyncCandidate> get() = plan.effective

        /**
         * 必须让用户看到的条目：冲突、歧义身份、墓碑删除、无可用地址的创建（规划器标了
         * requiresUserApproval 的），以及本端不支持的协议（UNSUPPORTED）。
         */
        val needsApproval: List<SourceSyncCandidate>
            get() = plan.items.filter {
                it.requiresUserApproval || it.action == SourceSyncAction.UNSUPPORTED
            }

        /** 无动作的条目（已同步 / 本地更新）。 */
        val ignoredItems: List<SourceSyncCandidate>
            get() = plan.items.filter {
                it.action == SourceSyncAction.NO_CHANGE || it.action == SourceSyncAction.IGNORE_STALE
            }

        val hasChanges: Boolean get() = summary.hasChanges
    }
}

/**
 * 来源同步预演（W6-B）：远端信封 JSON + 本机来源 -> 与 Windows SourceSyncPlanner 同形状的计划。
 *
 * 三条硬约束：
 * 1. **零写入**：只调用 [SyncSourceStore] 的读方法；接口上没有写方法，编译期即保证（测试另有调用计数）。
 * 2. **不另写一套判定**：完全复用 [SourceSyncPlanner]（与 Windows SourceSyncPlanner.cs 逐条对齐，
 *    87 例契约测试 + 黄金样本互操作已验证）。
 * 3. **不依赖 UI / Android Context**：纯 Kotlin + kotlinx.serialization，可在 JVM 单测里直接跑。
 *
 * 本轮只做预演：任何 plan 结果都不会落库（"应用变更"与逐条审批 UI 是后续任务）。
 */
class SourceSyncPreviewService(
    private val localSources: SyncSourceStore,
) {

    /** 解析失败不抛异常：返回 [SourceSyncPreview.Rejected]，交给调用方展示可读错误。 */
    suspend fun preview(envelopeJson: String): SourceSyncPreview =
        when (val decoded = SourceSyncCodec.tryDecode(envelopeJson)) {
            is SourceSyncCodec.DecodeResult.Failed ->
                SourceSyncPreview.Rejected(decoded.reason, decoded.message)
            is SourceSyncCodec.DecodeResult.Ok -> previewOf(decoded.envelope)
        }

    /** 已解析信封的入口（扫码/传输层若已自己 decode 过，可直接走这里，避免二次解析）。 */
    suspend fun previewOf(envelope: SourceSyncEnvelope): SourceSyncPreview.Ready {
        val locals = localSources.listLocalSources()
        val plan = SourceSyncPlanner.plan(envelope, locals)
        return SourceSyncPreview.Ready(
            plan = plan,
            summary = SourceSyncSummary.fromPlan(plan),
            deviceId = envelope.deviceId,
            snapshotId = envelope.snapshotId,
        )
    }
}
