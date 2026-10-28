package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncSummary

/**
 * 导入结果的四个桶（W6-F）。
 *
 * - APPLY：本机会新建/更新的条目（规划器给出的 Create/Update 且不需要确认）。**只有这一桶可能写库**。
 * - REQUIRES_APPROVAL：冲突、歧义身份、墓碑删除、无可用地址的新建、Adopt —— 都要人工逐条处理。
 * - IGNORED：NoChange / IgnoreStale（已同步或本地更新），本轮不需要动作。
 * - UNSUPPORTED：本机没有对应类型的 wire 值（含未知 sourceType）。**永远不允许"允许导入"**。
 *
 * 顺序即展示顺序；UNSUPPORTED 放最后，避免它被埋在"需确认"里看不见。
 */
enum class SourceSyncImportBucket {
    APPLY,
    REQUIRES_APPROVAL,
    IGNORED,
    UNSUPPORTED,
}

/**
 * 用户对单条的决策。
 *
 * [UNDECIDED] 是初值，也是**失败关闭**的默认值：没点过"允许导入"的条目在应用时会被剔除，
 * 不会被静默写库（宁可少导，也不替用户做主）。
 */
enum class SourceSyncEntryDecision {
    UNDECIDED,
    ALLOW,
    SKIP,
}

/**
 * 预览里的一条来源。
 *
 * [allowable] 用规划器的结果算出来，不由 UI 自己判断：只有"本服务真的能写"的条目才可允许，
 * 其余条目给固定说明（[blockReason]）并禁用控件 —— 让用户对着一个点不动的按钮猜原因是最糟的体验。
 */
data class SourceSyncEntry(
    val key: String,
    val bucket: SourceSyncImportBucket,
    val candidate: SourceSyncCandidate,
    val allowable: Boolean,
    val blockReason: String?,
    val decision: SourceSyncEntryDecision,
) {
    /** 稳定身份：syncSourceId（去掉首尾空白）；没有 id 时由调用方给的位置键兜底。 */
    val syncSourceId: String get() = key

    /** 展示名兜底顺序：displayName → sourceType → syncSourceId。 */
    val title: String
        get() = candidate.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: candidate.sourceType?.trim()?.takeIf { it.isNotEmpty() }
            ?: key

    /** 次要信息：类型 · 归一化地址 · 规划器原因码。 */
    val detail: String
        get() = listOfNotNull(
            candidate.sourceType?.trim()?.takeIf { it.isNotEmpty() },
            candidate.normalizedUrl?.trim()?.takeIf { it.isNotEmpty() },
            candidate.reason.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" · ")

    val isAllowed: Boolean get() = decision == SourceSyncEntryDecision.ALLOW

    val isSkipped: Boolean get() = decision == SourceSyncEntryDecision.SKIP

    /**
     * 这条目当前状态的一行文字（界面固定显示）。
     *
     * 逐条审批最容易出的问题是"点了没反应"：按钮点了却没回执，用户会连点或以为已生效。
     * 所以三种决策各有独立文案，不可允许条目显示禁用原因。
     */
    val stateText: String
        get() = when {
            !allowable -> blockReason ?: SourceSyncImportCopy.NEVER_ALLOW_APPROVAL
            decision == SourceSyncEntryDecision.ALLOW -> SourceSyncImportCopy.ALLOWED_STATE
            decision == SourceSyncEntryDecision.SKIP -> SourceSyncImportCopy.SKIPPED_STATE
            else -> SourceSyncImportCopy.ALLOW_RISK
        }
}

/** 一个桶里的条目（同桶条目保持规划器给出的顺序）。 */
data class SourceSyncImportBucketView(
    val bucket: SourceSyncImportBucket,
    val entries: List<SourceSyncEntry>,
)

/**
 * 一次预览的分组视图。
 *
 * [skippedKeys] 是"应用时要剔除"的集合：**可允许但没被允许**的条目也算剔除（失败关闭），
 * 非可允许条目不在剔除集里 —— 它们本来就写不进去，剔除反而会让结果页少报一条冲突。
 */
data class SourceSyncImportPreviewView(
    val deviceId: String,
    val snapshotId: String,
    val summary: SourceSyncSummary,
    val buckets: List<SourceSyncImportBucketView>,
) {
    val entries: List<SourceSyncEntry> get() = buckets.flatMap { it.entries }

    fun entriesIn(bucket: SourceSyncImportBucket): List<SourceSyncEntry> =
        buckets.firstOrNull { it.bucket == bucket }?.entries.orEmpty()

    fun countIn(bucket: SourceSyncImportBucket): Int = entriesIn(bucket).size

    val allowableKeys: Set<String> get() = entries.filter { it.allowable }.map { it.key }.toSet()

    val allowedKeys: Set<String>
        get() = entries.filter { it.allowable && it.decision == SourceSyncEntryDecision.ALLOW }
            .map { it.key }
            .toSet()

    val skippedKeys: Set<String>
        get() = entries.filter { it.allowable && it.decision != SourceSyncEntryDecision.ALLOW }
            .map { it.key }
            .toSet()

    val allowCount: Int get() = allowedKeys.size

    val skipCount: Int get() = entries.count { it.allowable && it.decision == SourceSyncEntryDecision.SKIP }

    val undecidedCount: Int get() = entries.count { it.allowable && it.decision == SourceSyncEntryDecision.UNDECIDED }

    /** 允许数 > 0 才允许点"应用"：一次都不允许等于什么都不导，按钮应当点不动而不是"成功导入 0 条"。 */
    val canApply: Boolean get() = allowCount > 0

    /** 只改一条的决策；非可允许条目保持原样（不能通过构造状态绕过禁用控件）。 */
    fun withDecision(key: String, decision: SourceSyncEntryDecision): SourceSyncImportPreviewView =
        copy(
            buckets = buckets.map { view ->
                view.copy(
                    entries = view.entries.map { entry ->
                        if (entry.key == key && entry.allowable) entry.copy(decision = decision) else entry
                    },
                )
            },
        )
}

/** 页面状态机。IDLE → READY_TO_PREVIEW →（PREVIEWED | BLOCKED）→ APPLYING → APPLIED。 */
enum class SourceSyncImportStatus {
    IDLE,
    READY_TO_PREVIEW,
    PREVIEWED,
    APPLYING,
    APPLIED,
    BLOCKED,
}

/**
 * 一条真正落库的来源（W6-H 的入口）。
 *
 * W6-F 的结果页只报导入了几条，但导入后每条来源都处于没有凭据的状态；
 * 要让用户点「补录并验证」，结果页必须知道这次到底落了哪些 serverId。
 * [sourceTypeRaw] 是信封里的 wire 值（可能是本机不支持的未知类型，这里只用于展示与映射）。
 */
data class SourceSyncAppliedSource(
    val serverId: String,
    val title: String,
    val sourceTypeRaw: String? = null,
    val syncSourceId: String? = null,
)

/**
 * 应用之后的摘要（只用于结果页，不再参与任何判定）。
 *
 * W6-81 起它同时装两类数：
 * - 规划桶（[requiresApprovalCount] / [ignoredCount] / [unsupportedCount]）来自预览，是「本来有多少」；
 * - 回执桶（[appliedCount] / [skippedCount] / [withheldCount] / [failedCount] / [removedCount]）来自
 *   这一轮真正发生的事，是「实际怎么处理了」。
 * 两者不可互推：需确认的条目压根不进闸门，回执里当然数不到它们。
 */
data class SourceSyncApplySummary(
    /** 真正写进库的条数（不含删除：删除走 [removedCount]，混在一起会让「已写入」撒谎）。 */
    val appliedCount: Int,
    val requiresApprovalCount: Int,
    val ignoredCount: Int,
    val unsupportedCount: Int,
    /** 批准了却写不下去的条数（存储层拒绝、记录缺失、删除被拒）。 */
    val failedCount: Int,
    /** 闸门裁掉、没打算写的条数（对端过旧、已同步、没人放行）。 */
    val skippedCount: Int = 0,
    /** 无条件扣住的条数（凭据、未知类型、本机不支持、需人工在来源页处理）。 */
    val withheldCount: Int = 0,
    /** 本轮连凭据一起移除的本地来源条数（对端声明删除）。 */
    val removedCount: Int = 0,
    /** 扣住的原因码（首现顺序，去重）；非空表示光点允许没用，得先去来源页处理。 */
    val blockingReasons: List<String> = emptyList(),
    val appliedTitles: List<String> = emptyList(),

    /** 真正写入的每一条来源（按结果顺序）；结果页据此渲染「补录并验证」入口。 */
    val appliedSources: List<SourceSyncAppliedSource> = emptyList(),

    /** W6-72：信封里被判为「次要端点、不参与同步」的条数（0 表示没有多端点来源）。 */
    val notImportedEndpointCount: Int = 0,

    /** W6-72：有多个端点的来源条数（用来区分「没多端点」与「有多端点但一条都没漏」）。 */
    val recordsWithAlternativeEndpoints: Int = 0,

    /** W6-72：本轮是否把真正写入的条目并进了对端基线。 */
    val baselineSaved: Boolean = false,

    /** W6-72：基线拒绝的条目数（合并不让条目倒退）。 */
    val baselineRejectedCount: Int = 0,
) {
    val totalCount: Int
        get() = appliedCount + requiresApprovalCount + ignoredCount + failedCount

    /** 没被写进去的条数：跳过 + 扣住 + 拒写（删除不算「没写」，它本来就是另一件事）。 */
    val notAppliedCount: Int get() = skippedCount + withheldCount + failedCount
}

/**
 * 导入页面状态。
 *
 * [blockedReason] 只有两种来源：整份信封被拒（reason 用契约层的码），或"一条都没允许"这种
 * 应用前拦截；两者都在界面上以同一个提示条展示，避免用户点了按钮没有任何反应。
 */
data class SourceSyncImportState(
    val status: SourceSyncImportStatus = SourceSyncImportStatus.IDLE,
    val fileName: String? = null,
    val envelopeJson: String? = null,
    val preview: SourceSyncImportPreviewView? = null,
    val applied: SourceSyncApplySummary? = null,
    val blockedReason: String? = null,
    val blockedMessage: String? = null,
    val isBusy: Boolean = false,
) {
    val canPreview: Boolean
        get() = !isBusy &&
            !envelopeJson.isNullOrBlank() &&
            status != SourceSyncImportStatus.APPLIED

    val canApply: Boolean
        get() = status == SourceSyncImportStatus.PREVIEWED && !isBusy && preview?.canApply == true

    /** 预览已完成但一条都没允许：按钮禁用 + 显示固定说明（"应用"点不动必须有解释）。 */
    val applyBlockedHint: String?
        get() = when {
            status == SourceSyncImportStatus.APPLYING || status == SourceSyncImportStatus.APPLIED -> null
            blockedReason != null -> SourceSyncImportCopy.blockedText(blockedReason)
            status == SourceSyncImportStatus.PREVIEWED && preview?.canApply == false ->
                SourceSyncImportCopy.NOTHING_ALLOWED
            else -> null
        }
}
