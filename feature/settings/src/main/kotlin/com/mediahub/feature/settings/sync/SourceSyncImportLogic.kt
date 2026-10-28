package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncDigestReasons
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.database.sync.AndroidSourceSyncService

/** 页面上出现的固定文案。集中在这里，测试与文档引用同一份，避免"改了一句忘了另一处"。 */
object SourceSyncImportCopy {

    /**
     * 结果页固定免责句（W6-F 验收要求，逐字固定）。
     *
     * 导入只写来源记录本身，不搬运登录会话：把"导入成功"说成"已登录"会让用户以为能直接播放，
     * 然后在点开时才失败。
     */
    const val DISCLAIMER: String = "导入不代表已登录；请对每个来源点『验证』。"

    /** 允许导入前的风险确认（可允许条目必须显式看到这句话才点得下）。 */
    const val ALLOW_RISK: String = "允许导入会在本机新增/更新该来源记录（不含登录凭据）。"

    /** 未知类型 / 本端不支持的固定说明：控件禁用，且永不随版本"自动恢复允许"。 */
    const val NEVER_ALLOW_UNSUPPORTED: String = "本机暂不支持该来源类型，无法允许导入。"

    /** 冲突 / 删除 / 歧义身份：本端服务不会写这类条目，声称"允许"就是骗人。 */
    const val NEVER_ALLOW_APPROVAL: String =
        "冲突、删除与歧义身份需在来源页逐条处理，此处不能自动允许。"

    /** 用户点了"允许"之后的可见状态：点了按钮必须有回执，否则用户会重复点、或者以为没生效。 */
    const val ALLOWED_STATE: String = "已允许：应用时会写入本地来源记录（不含登录凭据）。"

    /** 用户点了"跳过"之后的可见状态。 */
    const val SKIPPED_STATE: String = "已跳过：本条不会被导入。"

    /** 已同步条目。 */
    const val ALREADY_SYNCED: String = "已同步，本轮无需动作。"

    /** 预览完成但一条都没允许时的说明。 */
    const val NOTHING_ALLOWED: String = "请至少允许一个条目；未允许的条目不会被导入。"

    const val NO_ENVELOPE: String = "还没有选择信封文件。"

    const val NO_PREVIEW: String = "请先预览，再应用。"

    const val PREVIEW_FAILED: String = "预览失败"

    const val APPLY_FAILED: String = "应用失败"

    /** 选到加密归档（.mharchive）但还没有口令时的说明。 */
    const val PASSPHRASE_REQUIRED: String = "这份文件是加密归档，请输入导出时设置的口令。"

    /** 口令不对 / 归档被改坏时的说明（具体原因码由 blockedReason 原样带出）。 */
    const val UNLOCK_FAILED: String = "解密失败"

    /**
     * 选文件区上方的说明。扩展名只是提示：读端按前 8 字节 magic 嗅探，
     * 明文信封与加密归档都可以是任意扩展名（W6-66 起两端统一写 .mharchive，
     * 旧 Windows CLI 写出的 .mhsync 仍能读）。
     */
    const val PICK_HINT: String =
        "选择另一台设备导出的信封文件（.json 的明文信封），" +
            "或加密归档（.mharchive，需要导出时设置的口令）。" +
            "扩展名只是提示，真正的依据是文件内容：旧的 .mhsync 归档同样能选。" +
            "预览不会写入任何数据。"

    /**
     * W6-72：信封里有多个端点、但只有主端点会被对端按「主端点优先」策略采用时的说明。
     *
     * 本端不删线路（全量保留），这句话讲的是「对面这次只认了主地址」，避免用户以为同步丢了线路。
     */
    fun notImportedEndpoints(summary: SourceSyncApplySummary): String =
        "另有 " + summary.notImportedEndpointCount + " 条次要线路未参与同步（" +
            summary.recordsWithAlternativeEndpoints + " 个来源有多条线路；本机线路一条未删）。"

    /** W6-72：本轮写进去的条目已并进该对端基线（下一轮只传差异）。 */
    const val BASELINE_SAVED: String = "已记入该对端的同步基线：下一轮只传变化的来源。"

    /** W6-72：基线拒绝的条目（合并不许让条目倒退，通常是本地/远端修订号交叉）。 */
    fun baselineRejected(count: Int): String =
        "有 " + count + " 条没能记入基线（修订号比已有记录旧或同号不同内容）；导入结果不受影响。"

    /** W6-82：本轮的「计划」——预览里怎么分的桶。与下面那行「结果」不是同一件事。 */
    fun planLine(summary: SourceSyncApplySummary): String =
        "本次计划：放行 " + summary.allowedCount +
            " 条 · 需确认 " + summary.requiresApprovalCount +
            " 条 · 忽略 " + summary.ignoredCount +
            " 条 · 暂不支持 " + summary.unsupportedCount +
            " 条 · 共 " + summary.plannedCount + " 条"

    /**
     * W6-82：本轮的「结果」——服务真正做了什么。
     *
     * 五个数各有出处、互不可推：写入与删除来自回执的 wrote / removedServerIds，
     * 跳过 / 扣住 / 拒写来自闸门与写入器的三个桶。合成一句会让「放行 3 条」看起来等于「写入 3 条」。
     */
    fun resultLine(summary: SourceSyncApplySummary): String =
        "本次结果：写入 " + summary.appliedCount +
            " 条 · 删除 " + summary.removedCount +
            " 条 · 跳过 " + summary.skippedCount +
            " 条 · 扣住 " + summary.withheldCount +
            " 条 · 拒写 " + summary.failedCount + " 条"

    /** W6-82：永久扣住的原因码；没有就返回 null（0 条时不该占版面）。 */
    fun blockingReasons(summary: SourceSyncApplySummary): String? =
        if (summary.blockingReasons.isEmpty()) {
            null
        } else {
            "永久扣住的原因：" + summary.blockingReasons.joinToString("、") + "。光点允许没用，需去来源页逐条处理。"
        }

    /** W6-82：逐条失败区的标题；一条都没有时返回 null（0 条不该占版面）。 */
    fun refusedHeader(summary: SourceSyncApplySummary): String? =
        if (summary.refusedEntries.isEmpty()) {
            null
        } else {
            "未写入 " + summary.refusedEntries.size + " 条（逐条原因）"
        }

    /** W6-82：一条失败的可读句：「- 标题（身份）· 写入/删除 · 原因」。 */
    fun refusalLine(entry: SourceSyncRefusedEntry): String {
        val action = when (entry.action) {
            SourceSyncRefusedAction.WRITE -> "写入"
            SourceSyncRefusedAction.DELETE -> "删除"
        }
        return "- " + entry.title + "（" + entry.syncSourceId + "）· " + action + " · " +
            refusalReason(entry.reason)
    }

    /**
     * W6-82：原因码 → 人话。
     *
     * 码不认识就原样带出去（`原因码：xxx`），绝不返回空串：空串会让结果页出现
     * 「- 标题（身份）· 写入 · 」这种半句话，看起来像界面坏了，也把真正的原因丢了。
     */
    fun refusalReason(code: String): String {
        val raw = code.trim()
        return when {
            raw == SourceSyncReasons.UNKNOWN_SOURCE_TYPE ->
                "本机不认识这个来源类型（版本差异），升级后再试。"
            raw == SourceSyncReasons.NO_USABLE_ENDPOINT ->
                "这条来源没有可拨号的地址，写进库也没法播放。"
            raw == SourceSyncDigestReasons.MISSING_SYNC_SOURCE_ID ->
                "对端没给这条来源身份，无法建立对应关系。"
            raw == AndroidSourceSyncService.SOURCE_VANISHED ->
                "要更新的本地记录在写入前消失了（可能刚被删掉）。"
            raw == AndroidSourceSyncService.DELETE_NOT_SUPPORTED ->
                "本机没有接删除能力，删除请求只能拒绝并上报。"
            raw == AndroidSourceSyncService.REMOVE_MISSING ->
                "对端说删除，但本机本来就没有这条来源。"
            raw == AndroidSourceSyncService.REMOVE_LOCAL_MEDIA ->
                "本地媒体源不参与跨端删除（避免删掉本机磁盘上的媒体）。"
            raw == AndroidSourceSyncService.REMOVE_FAILED ->
                "删除本地来源时存储层失败，凭据可能还在。"
            raw.startsWith(AndroidSourceSyncService.WRITE_FAILED) ->
                "本机存储拒绝了这次写入（" +
                    raw.substring(AndroidSourceSyncService.WRITE_FAILED.length).trimStart(':').trim()
                        .ifEmpty { "未给异常类型" } + "）。"
            else -> "原因码：" + raw
        }
    }

    /** 桶标题（四桶固定顺序）。 */
    fun titleOf(bucket: SourceSyncImportBucket): String = when (bucket) {
        SourceSyncImportBucket.APPLY -> "可导入"
        SourceSyncImportBucket.REQUIRES_APPROVAL -> "需确认"
        SourceSyncImportBucket.IGNORED -> "已忽略"
        SourceSyncImportBucket.UNSUPPORTED -> "暂不支持"
    }

    fun noteOf(bucket: SourceSyncImportBucket): String? = when (bucket) {
        SourceSyncImportBucket.APPLY -> ALLOW_RISK
        SourceSyncImportBucket.REQUIRES_APPROVAL -> NEVER_ALLOW_APPROVAL
        SourceSyncImportBucket.IGNORED -> ALREADY_SYNCED
        SourceSyncImportBucket.UNSUPPORTED -> NEVER_ALLOW_UNSUPPORTED
    }

    /** 拦截原因的可读化：原因码原样带上，便于对照 Windows CLI 的 plan --json。 */
    fun blockedText(reason: String): String = when (reason) {
        NOTHING_ALLOWED -> NOTHING_ALLOWED
        PREVIEW_FAILED -> PREVIEW_FAILED
        APPLY_FAILED -> APPLY_FAILED
        PASSPHRASE_REQUIRED -> PASSPHRASE_REQUIRED
        UNLOCK_FAILED -> UNLOCK_FAILED
        NO_ENVELOPE, NO_PREVIEW -> NO_ENVELOPE
        else -> "无法继续：$reason"
    }
}

/**
 * 四桶分组、可否允许、以及"允许/跳过"如何变成名单 —— 全部纯函数，可在 JVM 单测里直接跑（不依赖 Compose）。
 *
 * 判定只依赖 [SourceSyncCandidate] 里的字段，规则与 [com.mediahub.core.database.sync.AndroidSourceSyncService]
 * 的写入条件完全一致：**只有 Create/Update 且 requiresUserApproval=false 的条目才可允许**。
 * 界面因此不会出现"点了允许却没写库"的假动作。
 */
object SourceSyncImportLogic {

    /** 一条候选的展示桶。未知类型无条件进 UNSUPPORTED（即使规划器给了别的动作）。 */
    fun bucketOf(candidate: SourceSyncCandidate): SourceSyncImportBucket = when {
        candidate.action == SourceSyncAction.UNSUPPORTED -> SourceSyncImportBucket.UNSUPPORTED
        candidate.reason == SourceSyncReasons.UNKNOWN_SOURCE_TYPE -> SourceSyncImportBucket.UNSUPPORTED
        candidate.action == SourceSyncAction.NO_CHANGE ||
            candidate.action == SourceSyncAction.IGNORE_STALE -> SourceSyncImportBucket.IGNORED
        candidate.action == SourceSyncAction.CREATE || candidate.action == SourceSyncAction.UPDATE ->
            if (candidate.requiresUserApproval) {
                SourceSyncImportBucket.REQUIRES_APPROVAL
            } else {
                SourceSyncImportBucket.APPLY
            }
        else -> SourceSyncImportBucket.REQUIRES_APPROVAL
    }

    /**
     * 是否可允许。
     *
     * 与写入条件逐条对齐：只有 Create/Update 且规划器没标 requiresUserApproval 的条目，
     * 应用时才会真的落库；其余条目（冲突、删除、Adopt、未知类型、无可用地址）一律不可允许。
     */
    fun isAllowable(candidate: SourceSyncCandidate): Boolean =
        !candidate.requiresUserApproval &&
            (candidate.action == SourceSyncAction.CREATE || candidate.action == SourceSyncAction.UPDATE) &&
            bucketOf(candidate) == SourceSyncImportBucket.APPLY

    /** 不可允许时的固定说明；可允许时返回 null。 */
    fun blockReason(candidate: SourceSyncCandidate): String? {
        if (isAllowable(candidate)) return null
        return when (bucketOf(candidate)) {
            SourceSyncImportBucket.UNSUPPORTED -> SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED
            SourceSyncImportBucket.IGNORED -> SourceSyncImportCopy.ALREADY_SYNCED
            else -> SourceSyncImportCopy.NEVER_ALLOW_APPROVAL
        }
    }

    /** 稳定身份键：syncSourceId（去空白）；缺失时用位置键兜底，保证界面行可控。 */
    fun keyOf(candidate: SourceSyncCandidate, index: Int): String =
        candidate.syncSourceId?.trim()?.takeIf { it.isNotEmpty() } ?: "entry-$index"

    /** 把规划器结果分桶。四桶恒定出现（空桶也保留），界面结构因此不随数据形状抖动。 */
    fun group(plan: SourceSyncPlan): List<SourceSyncImportBucketView> {
        val byBucket = LinkedHashMap<SourceSyncImportBucket, MutableList<SourceSyncEntry>>()
        SourceSyncImportBucket.entries.forEach { byBucket[it] = mutableListOf() }
        plan.items.forEachIndexed { index, candidate ->
            val bucket = bucketOf(candidate)
            val allowable = isAllowable(candidate)
            byBucket.getValue(bucket).add(
                SourceSyncEntry(
                    key = keyOf(candidate, index),
                    bucket = bucket,
                    candidate = candidate,
                    allowable = allowable,
                    blockReason = blockReason(candidate),
                    decision = if (allowable) {
                        SourceSyncEntryDecision.UNDECIDED
                    } else {
                        SourceSyncEntryDecision.SKIP
                    },
                ),
            )
        }
        return SourceSyncImportBucket.entries.map { bucket ->
            SourceSyncImportBucketView(bucket = bucket, entries = byBucket.getValue(bucket))
        }
    }

    /** 预览视图（含摘要）。 */
    fun previewView(deviceId: String, snapshotId: String, plan: SourceSyncPlan): SourceSyncImportPreviewView =
        SourceSyncImportPreviewView(
            deviceId = deviceId,
            snapshotId = snapshotId,
            summary = com.mediahub.core.common.sync.SourceSyncSummary.fromPlan(plan),
            buckets = group(plan),
        )

    /** 摘要行（界面顶部一句话）。计数与四桶一一对应，便于和 Windows CLI 的 counts 对照。 */
    fun summaryLine(view: SourceSyncImportPreviewView): String =
        "共 " + view.summary.total + " 条：" +
            "可导入 " + view.countIn(SourceSyncImportBucket.APPLY) +
            " · 需确认 " + view.countIn(SourceSyncImportBucket.REQUIRES_APPROVAL) +
            " · 已忽略 " + view.countIn(SourceSyncImportBucket.IGNORED) +
            " · 暂不支持 " + view.countIn(SourceSyncImportBucket.UNSUPPORTED)

    /** 键比较口径：去空白 + 忽略大小写（与同步层 syncSourceId 的比较一致）。 */
    fun normalizeKey(raw: String?): String = raw?.trim()?.lowercase().orEmpty()
}
