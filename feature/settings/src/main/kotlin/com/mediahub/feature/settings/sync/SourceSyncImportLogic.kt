package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncDigestReasons
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.database.sync.SourceSyncRemovalCodes
import com.mediahub.core.database.sync.SourceSyncRemovalStep

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

    /**
     * W6-82：本轮的「计划」——预览里怎么分的桶。与下面那行「结果」不是同一件事。
     *
     * W6-89：四个词改用 [bucketWord]，与桶标题和预览顶部那句逐字一致（旧文案写「放行 / 忽略」）。
     */
    fun planLine(summary: SourceSyncApplySummary): String =
        "本次计划：" + bucketWord(SourceSyncImportBucket.APPLY) + " " + summary.allowedCount +
            " 条 · " + bucketWord(SourceSyncImportBucket.REQUIRES_APPROVAL) + " " +
            summary.requiresApprovalCount +
            " 条 · " + bucketWord(SourceSyncImportBucket.IGNORED) + " " + summary.ignoredCount +
            " 条 · " + bucketWord(SourceSyncImportBucket.UNSUPPORTED) + " " +
            summary.unsupportedCount +
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

    /**
     * W6-83：被永久扣住的条目区标题；0 条时返回 null（0 条不该占版面）。
     *
     * 括号里那句是必要的：这一区的条目**点多少次允许都不会过**，不说清楚用户会以为按钮坏了。
     */
    fun withheldHeader(summary: SourceSyncApplySummary): String? =
        if (summary.withheldEntries.isEmpty()) {
            null
        } else {
            "保留未应用 " + summary.withheldEntries.size + " 条（扣住，点允许也不会写）"
        }

    /** W6-83：一条扣住的可读句：「- 标题（身份）· 原因」。 */
    fun withheldLine(entry: SourceSyncWithheldEntry): String =
        "- " + entry.title + "（" + entry.syncSourceId + "）· " + refusalReason(entry.reason)

    /** W6-84：「复制身份」按钮的文案（两端同一句）。 */
    const val COPY_IDENTITY: String = "复制身份"

    /**
     * W6-84：扣住条目的「下一步」。
     *
     * 原因句说的是**发生了什么**，这一句说的是**用户还能做什么**；两者不能合并，
     * 否则用户读完只知道「被扣住」却不知道路在哪。码不认识时返回 null —— 编建议比不说更害人。
     */
    fun nextStepOf(entry: SourceSyncWithheldEntry): String? = nextStepOfCode(entry.reason)

    /** W6-85：预览页条目也走同一张表 —— 码来自规划器的 candidate.reason，不是桶名。 */
    fun nextStepOf(entry: SourceSyncEntry): String? = nextStepOfCode(entry.candidate.reason)

    /**
     * W6-84 / W6-85：原因码 → 「下一步」的**唯一**出口。
     *
     * 结果页（扣住 / 拒写）与预览页共用这张表：同一个码在两处说法必须一致，否则用户
     * 先在预览里读到一句、应用后又读到另一句，会以为规则变了。码不认识时返回 null ——
     * 编建议比不说更害人。
     */
    fun nextStepOfCode(code: String): String? = when (code.trim()) {
        SyncRoundReasons.SECRETS_REQUIRE_APPROVAL ->
            "下一步：在对端把这条来源的凭据清空后重新导出信封，或在本机手动新建一条同样的来源。"
        SyncRoundReasons.NOT_SUPPORTED ->
            "下一步：等本机版本支持这种来源类型；再点几次允许也不会进来。"
        SourceSyncReasons.UNKNOWN_SOURCE_TYPE ->
            "下一步：把两端升到同一版本后重新导出信封。"
        SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT ->
            "下一步：这条两端内容不一致（版本号相同），本页一律不放行；请在来源页手工对齐后再导出。"
        SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY ->
            "下一步：本机有多条来源指向同一身份，先在来源页删掉多余的再重新导出。"
        else -> null
    }

    /**
     * W6-84：复制扣住条目时写进剪贴板的载荷 —— 对端给的身份原文（去首尾空白）。
     *
     * 载荷必须是身份而不是标题：标题在本机可以重名，身份才是能拿去对端机器检索的那把钥匙。
     */
    fun copyPayloadOf(entry: SourceSyncWithheldEntry): String = entry.syncSourceId.trim()

    /** W6-84：拒写条目同理 —— 复制的也是身份原文。 */
    fun copyPayloadOf(entry: SourceSyncRefusedEntry): String = entry.syncSourceId.trim()

    /**
     * W6-85：预览页条目复制的载荷。
     *
     * 只有真拿到对端身份（syncSourceId）才值得复制；缺身份的行用的是位置键（`entry-3`），
     * 抄出去在对端检索不到任何东西，因此返回 null、界面不出现按钮。
     */
    fun copyPayloadOf(entry: SourceSyncEntry): String? =
        entry.candidate.syncSourceId?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * W6-89：写入区的标题；一条都没写成时返回 null（0 条不该占版面）。
     *
     * 改前这里只有一串「- 标题」：[SourceSyncAppliedSource] 里明明跟着对端身份与本机 serverId，
     * 界面却什么都不说，用户没法把「这次写进去的」和自己来源页里的那条对上号。
     */
    fun writtenHeader(summary: SourceSyncApplySummary): String? =
        if (summary.appliedSources.isEmpty()) {
            null
        } else {
            "已写入 " + summary.appliedSources.size + " 条（按对端身份，可逐条核对）"
        }

    /** W6-89：一条写入的可读句：「- 标题（身份）」；身份缺失时只给标题，不编假身份。 */
    fun writtenLine(entry: SourceSyncAppliedSource): String {
        val identity = entry.syncSourceId?.trim()?.takeIf { it.isNotEmpty() }
        return if (identity == null) "- " + entry.title else "- " + entry.title + "（" + identity + "）"
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
            raw == SyncRoundReasons.SECRETS_REQUIRE_APPROVAL ->
                "对端带了凭据，本机不收对端凭据（能力声明 canSendSecrets=false）；要用这条来源，请在来源页手动补凭据。"
            raw == SyncRoundReasons.NOT_SUPPORTED ->
                "本机没有能接住这条来源的类型（原因码沿用合同里的 not-supported-on-windows），只能等版本支持。"
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
            SourceSyncRemovalCodes.stepCodeOf(raw) != null ->
                "删除本地来源时卡在「" +
                    removalStepLabel(SourceSyncRemovalCodes.stepCodeOf(raw).orEmpty()) +
                    "」这一步，凭据可能还在；到来源页手动删一次即可。"
            raw.startsWith(AndroidSourceSyncService.WRITE_FAILED) ->
                "本机存储拒绝了这次写入（" +
                    raw.substring(AndroidSourceSyncService.WRITE_FAILED.length).trimStart(':').trim()
                        .ifEmpty { "未给异常类型" } + "）。"
            else -> "原因码：" + raw
        }
    }

    /**
     * W6-88：删除链步骤码 → 界面用的人话。
     *
     * 码表权威在 core:database 的 [SourceSyncRemovalStep]（写码与翻译码分属两个模块，步骤码只能有一处）。
     * 认不出的步骤码原样带出，理由同 [refusalReason]：宁可显示 `servers-row`，也不能把原因吞成半句话。
     */
    fun removalStepLabel(code: String): String {
        val raw = code.trim()
        return REMOVAL_STEP_LABELS[raw] ?: raw
    }

    private val REMOVAL_STEP_LABELS: Map<String, String> = mapOf(
        SourceSyncRemovalStep.SERVERS.code to "删来源行",
        SourceSyncRemovalStep.ACCOUNTS.code to "删账号",
        SourceSyncRemovalStep.TOKENS.code to "清会话令牌",
        SourceSyncRemovalStep.CREDENTIALS.code to "清凭据",
        SourceSyncRemovalStep.PROGRESS.code to "删播放进度",
        SourceSyncRemovalStep.SUBTITLE_MEMORY.code to "删字幕记忆",
        SourceSyncRemovalStep.ICON.code to "删自定义图标",
        SourceSyncRemovalStep.SESSION_STORE.code to "清播放会话",
    )

    /**
     * W6-89：四桶的唯一词汇表。
     *
     * 改前同一个概念有两套词：桶标题与预览顶部说「可导入 / 已忽略」，结果卡的「计划」行说
     * 「放行 / 忽略」。用户在上下两处看到不同的词，会以为说的是两件事。四个词只在这里定义一次，
     * [titleOf] 与 [planLine]、[summaryLine] 都从这里取。
     */
    fun bucketWord(bucket: SourceSyncImportBucket): String = when (bucket) {
        SourceSyncImportBucket.APPLY -> "可导入"
        SourceSyncImportBucket.REQUIRES_APPROVAL -> "需确认"
        SourceSyncImportBucket.IGNORED -> "已忽略"
        SourceSyncImportBucket.UNSUPPORTED -> "暂不支持"
    }

    /** 桶标题（四桶固定顺序），词取自 [bucketWord]。 */
    fun titleOf(bucket: SourceSyncImportBucket): String = bucketWord(bucket)

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

    /**
     * 摘要行（界面顶部一句话）。计数与四桶一一对应，便于和 Windows CLI 的 counts 对照。
     *
     * W6-89：词同样取自 [bucketWord]，和结果卡的「计划」行只差「共」字的位置。
     */
    fun summaryLine(view: SourceSyncImportPreviewView): String =
        "共 " + view.summary.total + " 条：" +
            SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.APPLY) + " " +
            view.countIn(SourceSyncImportBucket.APPLY) +
            " · " + SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.REQUIRES_APPROVAL) + " " +
            view.countIn(SourceSyncImportBucket.REQUIRES_APPROVAL) +
            " · " + SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.IGNORED) + " " +
            view.countIn(SourceSyncImportBucket.IGNORED) +
            " · " + SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.UNSUPPORTED) + " " +
            view.countIn(SourceSyncImportBucket.UNSUPPORTED)

    /** 键比较口径：去空白 + 忽略大小写（与同步层 syncSourceId 的比较一致）。 */
    fun normalizeKey(raw: String?): String = raw?.trim()?.lowercase().orEmpty()
}
