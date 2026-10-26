package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons

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
 * 四桶分组、可否允许、以及"跳过"如何生效 —— 全部纯函数，可在 JVM 单测里直接跑（不依赖 Compose）。
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

    /**
     * 应用前把"未允许"的来源从信封里剔除，再交给 [com.mediahub.core.database.sync.AndroidSourceSyncService]。
     *
     * 为什么在信封上做而不是给服务加逐条参数：服务的公开契约是"一份信封 → 一次导入"，逐条决策
     * 是 UI 层的事；过滤信封既不改服务签名，也能让结果页的计数如实反映"我批准了什么"。
     *
     * 信封解析失败、或过滤不产生变化时原样返回（宁可让服务自己报错，也不在这里制造第二种错误码）。
     */
    fun filterEnvelope(envelopeJson: String, skippedSyncSourceIds: Set<String>): String {
        if (skippedSyncSourceIds.isEmpty()) return envelopeJson
        val skipped = skippedSyncSourceIds.map(::normalizeKey).filter { it.isNotEmpty() }.toSet()
        if (skipped.isEmpty()) return envelopeJson
        val decoded = SourceSyncCodec.tryDecode(envelopeJson)
        if (decoded !is SourceSyncCodec.DecodeResult.Ok) return envelopeJson
        val envelope = decoded.envelope
        val sources = envelope.sources.filterNot { normalizeKey(it.syncSourceId) in skipped }
        val deletions = envelope.deletions.filterNot { normalizeKey(it.syncSourceId) in skipped }
        if (sources.size == envelope.sources.size && deletions.size == envelope.deletions.size) {
            return envelopeJson
        }
        return runCatching {
            SourceSyncCodec.encode(envelope.copy(sources = sources, deletions = deletions))
        }.getOrDefault(envelopeJson)
    }
}
