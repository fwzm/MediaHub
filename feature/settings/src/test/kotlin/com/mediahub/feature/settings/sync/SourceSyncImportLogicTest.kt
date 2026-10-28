package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import com.mediahub.core.common.sync.SyncRoundReasons
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.database.sync.SourceSyncRemovalCodes
import com.mediahub.core.database.sync.SourceSyncRemovalStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-F 四桶分组与"可否允许"的规则（纯 JVM 证据，不依赖 Compose / Robolectric）。
 *
 * 这些规则是"不会把不支持的东西放进来"的最后一道闸：写入条件在 AndroidSourceSyncService 里是
 * requiresUserApproval == false 且动作是 Create/Update，所以界面必须与它逐条对齐。
 */
class SourceSyncImportLogicTest {

    private fun candidate(
        action: SourceSyncAction,
        reason: String = "",
        id: String? = "sync-1",
        requiresApproval: Boolean = false,
        knownType: Boolean = true,
        sourceType: String = "emby",
    ) = SourceSyncCandidate(
        action = action,
        reason = reason,
        syncSourceId = id,
        sourceType = sourceType,
        isKnownSourceType = knownType,
        displayName = "客厅 " + sourceType,
        requiresUserApproval = requiresApproval,
    )

    @Test
    fun unknownSourceTypeGoesToUnsupportedEvenWhenTheActionLooksImportable() {
        val unknown = candidate(
            action = SourceSyncAction.CREATE,
            reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
            knownType = false,
            sourceType = "quantum-nas",
        )

        assertEquals(SourceSyncImportBucket.UNSUPPORTED, SourceSyncImportLogic.bucketOf(unknown))
        assertFalse(SourceSyncImportLogic.isAllowable(unknown))
        assertEquals(
            SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED,
            SourceSyncImportLogic.blockReason(unknown),
        )
    }

    @Test
    fun unsupportedActionIsNeverAllowable() {
        val unsupported = candidate(
            action = SourceSyncAction.UNSUPPORTED,
            reason = SourceSyncReasons.NOT_SUPPORTED_ON_WINDOWS,
            sourceType = "quark-drive",
        )

        assertEquals(SourceSyncImportBucket.UNSUPPORTED, SourceSyncImportLogic.bucketOf(unsupported))
        assertFalse(SourceSyncImportLogic.isAllowable(unsupported))
    }

    @Test
    fun noChangeAndStaleEntriesSitInTheIgnoredBucket() {
        val same = candidate(SourceSyncAction.NO_CHANGE, SourceSyncReasons.UP_TO_DATE)
        val stale = candidate(SourceSyncAction.IGNORE_STALE, SourceSyncReasons.STALE_REVISION, id = "sync-2")

        assertEquals(SourceSyncImportBucket.IGNORED, SourceSyncImportLogic.bucketOf(same))
        assertEquals(SourceSyncImportBucket.IGNORED, SourceSyncImportLogic.bucketOf(stale))
        assertFalse(SourceSyncImportLogic.isAllowable(same))
        assertEquals(SourceSyncImportCopy.ALREADY_SYNCED, SourceSyncImportLogic.blockReason(stale))
    }

    @Test
    fun approvalFlaggedCreateMovesToRequiresApproval() {
        val needingApproval = candidate(SourceSyncAction.CREATE, requiresApproval = true)

        assertEquals(
            SourceSyncImportBucket.REQUIRES_APPROVAL,
            SourceSyncImportLogic.bucketOf(needingApproval),
        )
        assertFalse(SourceSyncImportLogic.isAllowable(needingApproval))
        assertEquals(
            SourceSyncImportCopy.NEVER_ALLOW_APPROVAL,
            SourceSyncImportLogic.blockReason(needingApproval),
        )
    }

    @Test
    fun conflictsAndDeletesAndAdoptsAreNotAllowable() {
        val conflict = candidate(SourceSyncAction.CONFLICT, SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT)
        val delete = candidate(SourceSyncAction.DELETE, SourceSyncReasons.TOMBSTONE, id = "sync-3")
        val adopt = candidate(SourceSyncAction.ADOPT, SourceSyncReasons.LEGACY_IDENTITY_MATCH, id = "sync-4")

        listOf(conflict, delete, adopt).forEach { entry ->
            assertFalse(SourceSyncImportLogic.isAllowable(entry))
            assertEquals(
                SourceSyncImportBucket.REQUIRES_APPROVAL,
                SourceSyncImportLogic.bucketOf(entry),
            )
        }
    }

    @Test
    fun plainCreateAndUpdateAreAllowable() {
        val create = candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE)
        val update = candidate(SourceSyncAction.UPDATE, SourceSyncReasons.HIGHER_REVISION, id = "sync-2")

        assertTrue(SourceSyncImportLogic.isAllowable(create))
        assertTrue(SourceSyncImportLogic.isAllowable(update))
        assertEquals(SourceSyncImportBucket.APPLY, SourceSyncImportLogic.bucketOf(update))
        assertNull(SourceSyncImportLogic.blockReason(create))
    }

    @Test
    fun groupAlwaysRendersFourBucketsAndDefaultsToSkipWhenNotAllowable() {
        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.CONFLICT, SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY, id = "sync-2"),
            ),
        )

        val buckets = SourceSyncImportLogic.group(plan)

        assertEquals(4, buckets.size)
        assertEquals(
            listOf(
                SourceSyncImportBucket.APPLY,
                SourceSyncImportBucket.REQUIRES_APPROVAL,
                SourceSyncImportBucket.IGNORED,
                SourceSyncImportBucket.UNSUPPORTED,
            ),
            buckets.map { it.bucket },
        )
        val apply = buckets[0].entries.single()
        val conflict = buckets[1].entries.single()
        assertEquals(SourceSyncEntryDecision.UNDECIDED, apply.decision)
        assertEquals(SourceSyncEntryDecision.SKIP, conflict.decision)
        assertTrue(apply.allowable)
        assertFalse(conflict.allowable)
    }

    @Test
    fun emptyPlanStillHasFourEmptyBuckets() {
        val buckets = SourceSyncImportLogic.group(SourceSyncPlan(emptyList()))

        assertEquals(4, buckets.size)
        assertTrue(buckets.all { it.entries.isEmpty() })
    }

    @Test
    fun previewViewTreatsSilenceAsSkipButCountsNothingAsAllowed() {
        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE, id = "sync-2"),
            ),
        )

        val view = SourceSyncImportLogic.previewView("device-a", "snap-1", plan)

        assertEquals(setOf("sync-1", "sync-2"), view.allowableKeys)
        assertTrue(view.allowedKeys.isEmpty())
        assertEquals(setOf("sync-1", "sync-2"), view.skippedKeys)
        assertFalse(view.canApply)
        assertEquals(2, view.undecidedCount)
    }

    @Test
    fun allowingOneEntryShrinksTheSkippedSetAndEnablesApply() {
        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE, id = "sync-2"),
            ),
        )

        val view = SourceSyncImportLogic.previewView("device-a", "snap-1", plan)
            .withDecision("sync-1", SourceSyncEntryDecision.ALLOW)

        assertEquals(setOf("sync-1"), view.allowedKeys)
        assertEquals(setOf("sync-2"), view.skippedKeys)
        assertTrue(view.canApply)
    }

    @Test
    fun summaryLineCountsEveryBucket() {
        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.NO_CHANGE, SourceSyncReasons.UP_TO_DATE, id = "sync-2"),
                candidate(SourceSyncAction.UNSUPPORTED, SourceSyncReasons.UNKNOWN_SOURCE_TYPE, id = "sync-3", knownType = false),
            ),
        )

        val line = SourceSyncImportLogic.summaryLine(
            SourceSyncImportLogic.previewView("device-a", "snap-1", plan),
        )

        assertTrue(line.contains("可导入 1"))
        assertTrue(line.contains("已忽略 1"))
        assertTrue(line.contains("暂不支持 1"))
    }

    @Test
    fun keyFallsBackToAPositionKeyWhenTheIdIsMissing() {
        assertEquals("sync-1", SourceSyncImportLogic.keyOf(candidate(SourceSyncAction.CREATE), 0))
        assertEquals("entry-3", SourceSyncImportLogic.keyOf(candidate(SourceSyncAction.CREATE, id = "   "), 3))
        assertEquals("entry-5", SourceSyncImportLogic.keyOf(candidate(SourceSyncAction.CREATE, id = null), 5))
    }

    @Test
    fun everyEntryCarriesAVisibleStateLineForItsDecision() {
        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.UNSUPPORTED, SourceSyncReasons.UNKNOWN_SOURCE_TYPE, id = "sync-9", knownType = false),
            ),
        )
        val preview = SourceSyncImportLogic.previewView("device-a", "snap-1", plan)
        val before = preview.entries.first { it.key == "sync-1" }
        assertEquals(SourceSyncImportCopy.ALLOW_RISK, before.stateText)

        val allowed = preview.withDecision("sync-1", SourceSyncEntryDecision.ALLOW)
            .entries.first { it.key == "sync-1" }
        assertEquals(SourceSyncImportCopy.ALLOWED_STATE, allowed.stateText)

        val skipped = preview.withDecision("sync-1", SourceSyncEntryDecision.SKIP)
            .entries.first { it.key == "sync-1" }
        assertEquals(SourceSyncImportCopy.SKIPPED_STATE, skipped.stateText)

        val unsupported = preview.entries.first { it.key == "sync-9" }
        assertFalse(unsupported.allowable)
        assertEquals(SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED, unsupported.stateText)
    }

    @Test
    fun thePlanLineAndTheResultLineNeverShareACount() {
        val summary = SourceSyncApplySummary(
            appliedCount = 2,
            requiresApprovalCount = 4,
            ignoredCount = 5,
            unsupportedCount = 1,
            failedCount = 1,
            skippedCount = 3,
            withheldCount = 4,
            removedCount = 1,
            allowedCount = 3,
            plannedCount = 13,
        )

        assertEquals(
            "本次计划：可导入 3 条 · 需确认 4 条 · 已忽略 5 条 · 暂不支持 1 条 · 共 13 条",
            SourceSyncImportCopy.planLine(summary),
        )
        assertEquals(
            "本次结果：写入 2 条 · 删除 1 条 · 跳过 3 条 · 扣住 4 条 · 拒写 1 条",
            SourceSyncImportCopy.resultLine(summary),
        )
        assertEquals(8, summary.notAppliedCount)
        assertEquals(
            "保留未应用 2 条（扣住，点允许也不会写）",
            SourceSyncImportCopy.withheldHeader(
                summary.copy(
                    withheldEntries = listOf(
                        SourceSyncWithheldEntry("sync-a", "客厅 Emby", SyncRoundReasons.SECRETS_REQUIRE_APPROVAL),
                        SourceSyncWithheldEntry("sync-b", "书房 Emby", SyncRoundReasons.NOT_SUPPORTED),
                    ),
                ),
            ),
        )
    }

    @Test
    fun anEmptyReceiptStillSaysEveryCountAndHasNoFailureBlock() {
        val summary = SourceSyncApplySummary(
            appliedCount = 0,
            requiresApprovalCount = 0,
            ignoredCount = 0,
            unsupportedCount = 0,
            failedCount = 0,
        )

        assertEquals(
            "本次计划：可导入 0 条 · 需确认 0 条 · 已忽略 0 条 · 暂不支持 0 条 · 共 0 条",
            SourceSyncImportCopy.planLine(summary),
        )
        assertEquals(
            "本次结果：写入 0 条 · 删除 0 条 · 跳过 0 条 · 扣住 0 条 · 拒写 0 条",
            SourceSyncImportCopy.resultLine(summary),
        )
        assertNull(SourceSyncImportCopy.refusedHeader(summary))
        assertNull(SourceSyncImportCopy.withheldHeader(summary))
    }

    @Test
    fun theSameBucketWordIsUsedInThePlanLineAndInThePreviewLine() {
        // W6-89：改前结果卡说「放行 / 忽略」，桶标题与预览顶部说「可导入 / 已忽略」，
        // 同一个桶两套词。四个词现在只在 bucketWord 里定义一次。
        val words = SourceSyncImportBucket.values().map { SourceSyncImportCopy.bucketWord(it) }
        assertEquals(listOf("可导入", "需确认", "已忽略", "暂不支持"), words)
        SourceSyncImportBucket.values().forEach { bucket ->
            assertEquals(SourceSyncImportCopy.bucketWord(bucket), SourceSyncImportCopy.titleOf(bucket))
        }

        val summary = SourceSyncApplySummary(
            appliedCount = 1,
            requiresApprovalCount = 2,
            ignoredCount = 3,
            unsupportedCount = 4,
            failedCount = 0,
            allowedCount = 5,
            plannedCount = 14,
        )
        val planLine = SourceSyncImportCopy.planLine(summary)
        assertEquals("本次计划：可导入 5 条 · 需确认 2 条 · 已忽略 3 条 · 暂不支持 4 条 · 共 14 条", planLine)
        // 旧词一个都不许留下：留着就说明有地方又绕过了词汇表。
        assertFalse(planLine.contains("放行"))
        assertFalse(planLine.contains("· 忽略"))

        val plan = SourceSyncPlan(
            listOf(
                candidate(SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate(SourceSyncAction.NO_CHANGE, SourceSyncReasons.UP_TO_DATE, id = "sync-2"),
            ),
        )
        val previewLine = SourceSyncImportLogic.summaryLine(
            SourceSyncImportLogic.previewView("device-a", "snap-1", plan),
        )
        assertTrue(previewLine.contains(SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.APPLY) + " 1"))
        assertTrue(previewLine.contains(SourceSyncImportCopy.bucketWord(SourceSyncImportBucket.IGNORED) + " 1"))
    }

    @Test
    fun everyWrittenEntryIsListedWithItsRemoteIdentityBesideItsTitle() {
        val empty = SourceSyncApplySummary(appliedCount = 0, requiresApprovalCount = 0, ignoredCount = 0, unsupportedCount = 0, failedCount = 0)
        assertNull(SourceSyncImportCopy.writtenHeader(empty))

        val summary = SourceSyncApplySummary(
            appliedCount = 2,
            requiresApprovalCount = 0,
            ignoredCount = 0,
            unsupportedCount = 0,
            failedCount = 0,
            appliedSources = listOf(
                SourceSyncAppliedSource(serverId = "srv-1", title = "客厅 Emby", sourceTypeRaw = "emby", syncSourceId = "local-a"),
                SourceSyncAppliedSource(serverId = "srv-2", title = "书房 Jellyfin", sourceTypeRaw = "jellyfin", syncSourceId = null),
            ),
        )

        assertEquals("已写入 2 条（按对端身份，可逐条核对）", SourceSyncImportCopy.writtenHeader(summary))
        assertEquals("- 客厅 Emby（local-a）", SourceSyncImportCopy.writtenLine(summary.appliedSources[0]))
        // 身份缺失时只给标题：宁可少说一句，也不拿 serverId 或标题冒充身份。
        assertEquals("- 书房 Jellyfin", SourceSyncImportCopy.writtenLine(summary.appliedSources[1]))
        assertEquals(
            "- 客厅 Emby（local-a）",
            SourceSyncImportCopy.writtenLine(summary.appliedSources[0].copy(syncSourceId = "  local-a  ")),
        )
    }

    @Test
    fun aRefusalIsNamedWithItsIdentityItsActionAndAReasonSentence() {
        val summary = SourceSyncApplySummary(
            appliedCount = 0,
            requiresApprovalCount = 0,
            ignoredCount = 0,
            unsupportedCount = 0,
            failedCount = 2,
            refusedEntries = listOf(
                SourceSyncRefusedEntry(
                    syncSourceId = "sync-a",
                    title = "客厅 Emby",
                    action = SourceSyncRefusedAction.WRITE,
                    reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
                ),
                SourceSyncRefusedEntry(
                    syncSourceId = "sync-b",
                    title = "旧 NAS",
                    action = SourceSyncRefusedAction.DELETE,
                    reason = AndroidSourceSyncService.DELETE_NOT_SUPPORTED,
                ),
            ),
        )

        assertEquals("未写入 2 条（逐条原因）", SourceSyncImportCopy.refusedHeader(summary))
        assertEquals(
            listOf(
                "- 客厅 Emby（sync-a）· 写入 · 本机不认识这个来源类型（版本差异），升级后再试。",
                "- 旧 NAS（sync-b）· 删除 · 本机没有接删除能力，删除请求只能拒绝并上报。",
            ),
            summary.refusedEntries.map { SourceSyncImportCopy.refusalLine(it) },
        )
    }

    @Test
    fun anUnknownReasonCodeIsShownVerbatimInsteadOfSwallowed() {
        assertEquals("原因码：something-new:42", SourceSyncImportCopy.refusalReason("something-new:42"))
        assertEquals(
            "本机存储拒绝了这次写入（IOException）。",
            SourceSyncImportCopy.refusalReason(AndroidSourceSyncService.WRITE_FAILED + ":IOException"),
        )
        assertEquals(
            "本机存储拒绝了这次写入（未给异常类型）。",
            SourceSyncImportCopy.refusalReason(AndroidSourceSyncService.WRITE_FAILED),
        )
    }

    @Test
    fun aWithheldEntryIsNamedWithItsIdentityAndItsOwnReason() {
        val summary = SourceSyncApplySummary(
            appliedCount = 1,
            requiresApprovalCount = 2,
            ignoredCount = 0,
            unsupportedCount = 1,
            failedCount = 0,
            withheldCount = 2,
            withheldEntries = listOf(
                SourceSyncWithheldEntry("sync-secret", "客厅 Emby", SyncRoundReasons.SECRETS_REQUIRE_APPROVAL),
                SourceSyncWithheldEntry("sync-odd", "书房 MyThing", SyncRoundReasons.NOT_SUPPORTED),
            ),
        )

        assertEquals("保留未应用 2 条（扣住，点允许也不会写）", SourceSyncImportCopy.withheldHeader(summary))
        assertEquals(
            listOf(
                "- 客厅 Emby（sync-secret）· 对端带了凭据，本机不收对端凭据（能力声明 canSendSecrets=false）；要用这条来源，请在来源页手动补凭据。",
                "- 书房 MyThing（sync-odd）· 本机没有能接住这条来源的类型（原因码沿用合同里的 not-supported-on-windows），只能等版本支持。",
            ),
            summary.withheldEntries.map { SourceSyncImportCopy.withheldLine(it) },
        )
    }

    @Test
    fun thePickHintNamesTheCanonicalArchiveSuffixAndNeverCallsTheLegacySuffixPlaintext() {
        assertTrue(SourceSyncImportCopy.PICK_HINT.contains(".mharchive"))
        assertTrue(SourceSyncImportCopy.PICK_HINT.contains(".json"))
        assertTrue(SourceSyncImportCopy.PICK_HINT.contains(".mhsync"))
        assertFalse(SourceSyncImportCopy.PICK_HINT.contains(".mhsync 的明文信封"))
    }

    @Test
    fun everyWithheldReasonThatHasAWayOutGetsItsOwnNextStep() {
        val secret = SourceSyncWithheldEntry("sync-secret", "客厅 Emby", SyncRoundReasons.SECRETS_REQUIRE_APPROVAL)
        val unsupported = SourceSyncWithheldEntry("sync-odd", "书房 MyThing", SyncRoundReasons.NOT_SUPPORTED)
        val unknownType = SourceSyncWithheldEntry("sync-quantum", "Quantum", SourceSyncReasons.UNKNOWN_SOURCE_TYPE)
        val noReason = SourceSyncWithheldEntry("sync-blank", "Blank", AndroidSourceSyncService.WITHHELD_WITHOUT_REASON)

        val steps = listOf(secret, unsupported, unknownType).map { SourceSyncImportCopy.nextStepOf(it) }
        assertEquals(3, steps.filterNotNull().toSet().size)
        assertEquals(
            "下一步：在对端把这条来源的凭据清空后重新导出信封，或在本机手动新建一条同样的来源。",
            steps[0],
        )
        assertEquals("下一步：等本机版本支持这种来源类型；再点几次允许也不会进来。", steps[1])
        assertEquals("下一步：把两端升到同一版本后重新导出信封。", steps[2])
        // 没有确定出路的码不编建议：空话比不说话更害人。
        assertNull(SourceSyncImportCopy.nextStepOf(noReason))
        assertNull(SourceSyncImportCopy.nextStepOf(SourceSyncWithheldEntry("sync-x", "X", "something-new:42")))
    }

    @Test
    fun copyingAStuckEntryCarriesTheIdentityNotTheTitle() {
        val withheld = SourceSyncWithheldEntry("  sync-secret  ", "客厅 Emby", SyncRoundReasons.SECRETS_REQUIRE_APPROVAL)
        val refused = SourceSyncRefusedEntry(
            syncSourceId = " sync-vanished ",
            title = "书房 Emby",
            action = SourceSyncRefusedAction.WRITE,
            reason = AndroidSourceSyncService.SOURCE_VANISHED,
        )

        assertEquals("sync-secret", SourceSyncImportCopy.copyPayloadOf(withheld))
        assertEquals("sync-vanished", SourceSyncImportCopy.copyPayloadOf(refused))
        assertEquals("复制身份", SourceSyncImportCopy.COPY_IDENTITY)
        // 载荷里绝不能出现标题（标题本机可以重名，身份才是能拿去对端检索的钥匙）。
        assertFalse(SourceSyncImportCopy.copyPayloadOf(withheld).contains("客厅"))
    }

    @Test
    fun aPreviewEntryBorrowsTheSameNextStepTableAsTheResultPage() {
        // 同一个码在两处必须说同一句话：否则用户先在预览里读到一句、应用后又读到另一句。
        assertEquals(
            SourceSyncImportCopy.nextStepOf(
                SourceSyncWithheldEntry("sync-secret", "客厅 Emby", SyncRoundReasons.SECRETS_REQUIRE_APPROVAL),
            ),
            SourceSyncImportCopy.nextStepOf(previewEntry(SyncRoundReasons.SECRETS_REQUIRE_APPROVAL, "sync-secret")),
        )
        assertEquals(
            "下一步：这条两端内容不一致（版本号相同），本页一律不放行；请在来源页手工对齐后再导出。",
            SourceSyncImportCopy.nextStepOf(previewEntry(SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT, "sync-conflict")),
        )
        assertEquals(
            "下一步：本机有多条来源指向同一身份，先在来源页删掉多余的再重新导出。",
            SourceSyncImportCopy.nextStepOf(previewEntry(SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY, "sync-ambig")),
        )
        assertEquals(
            "下一步：把两端升到同一版本后重新导出信封。",
            SourceSyncImportCopy.nextStepOf(previewEntry(SourceSyncReasons.UNKNOWN_SOURCE_TYPE, "sync-quantum")),
        )
        // 码不认识、或压根没给码：不编建议。
        assertNull(SourceSyncImportCopy.nextStepOf(previewEntry("something-new:42", "sync-x")))
        assertNull(SourceSyncImportCopy.nextStepOf(previewEntry("", "sync-quiet")))
    }

    @Test
    fun copyingAPreviewEntryCarriesTheRemoteIdentityAndNeverAPositionalKey() {
        assertEquals("sync-secret", SourceSyncImportCopy.copyPayloadOf(previewEntry("", "  sync-secret  ")))
        val noIdentity = SourceSyncEntry(
            key = "entry-3",
            bucket = SourceSyncImportBucket.UNSUPPORTED,
            candidate = SourceSyncCandidate(action = SourceSyncAction.UNSUPPORTED, syncSourceId = null),
            allowable = false,
            blockReason = SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED,
            decision = SourceSyncEntryDecision.UNDECIDED,
        )
        // 位置键（entry-3）抄出去在对端检索不到任何东西 —— 宁可不给按钮。
        assertNull(SourceSyncImportCopy.copyPayloadOf(noIdentity))
    }

    @Test
    fun aFailedRemovalStepIsTranslatedIntoPlainWords() {
        // 八步各有中文名：同一句「删除失败」对用户毫无用处，说的必须是哪一步。
        assertEquals(
            "删除本地来源时卡在「清播放会话」这一步，凭据可能还在；到来源页手动删一次即可。",
            SourceSyncImportCopy.refusalReason(
                SourceSyncRemovalCodes.failedAt(SourceSyncRemovalStep.SESSION_STORE),
            ),
        )
        assertEquals(
            "删除本地来源时卡在「清凭据」这一步，凭据可能还在；到来源页手动删一次即可。",
            SourceSyncImportCopy.refusalReason(
                SourceSyncRemovalCodes.failedAt(SourceSyncRemovalStep.CREDENTIALS),
            ),
        )
        // 认不出的步骤码原样带出：宁可显示 servers-row-2，也不吞成半句话。
        assertEquals(
            "删除本地来源时卡在「something-new」这一步，凭据可能还在；到来源页手动删一次即可。",
            SourceSyncImportCopy.refusalReason("local-source-remove-failed:something-new"),
        )
        // 不带步骤码的老码保持原句（既有回执可能还没升级）。
        assertEquals(
            "删除本地来源时存储层失败，凭据可能还在。",
            SourceSyncImportCopy.refusalReason(AndroidSourceSyncService.REMOVE_FAILED),
        )
    }

    @Test
    fun everyRemovalStepHasItsOwnChineseName() {
        val labels = mapOf(
            SourceSyncRemovalStep.SERVERS to "删来源行",
            SourceSyncRemovalStep.ACCOUNTS to "删账号",
            SourceSyncRemovalStep.TOKENS to "清会话令牌",
            SourceSyncRemovalStep.CREDENTIALS to "清凭据",
            SourceSyncRemovalStep.PROGRESS to "删播放进度",
            SourceSyncRemovalStep.SUBTITLE_MEMORY to "删字幕记忆",
            SourceSyncRemovalStep.ICON to "删自定义图标",
            SourceSyncRemovalStep.SESSION_STORE to "清播放会话",
        )
        // 八步一个不少、也不许共用一个名字（共用就等于没分辨）。
        assertEquals(SourceSyncRemovalStep.values().size, labels.size)
        labels.forEach { (step, label) ->
            assertEquals(label, SourceSyncImportCopy.removalStepLabel(step.code))
            assertEquals(label, SourceSyncImportCopy.removalStepLabel("  " + step.code + "  "))
        }
        assertEquals(labels.values.toSet().size, labels.size)
        // 认不出的码原样返回（trim 过）。
        assertEquals("something-new", SourceSyncImportCopy.removalStepLabel(" something-new "))
    }

    /** 预览页的一条不可允许条目：身份来自 candidate.syncSourceId，桶只是展示层。 */
    private fun previewEntry(reason: String, id: String?) = SourceSyncEntry(
        key = id ?: "entry-0",
        bucket = SourceSyncImportBucket.REQUIRES_APPROVAL,
        candidate = SourceSyncCandidate(
            action = SourceSyncAction.UPDATE,
            reason = reason,
            syncSourceId = id,
            requiresUserApproval = true,
        ),
        allowable = false,
        blockReason = SourceSyncImportCopy.NEVER_ALLOW_APPROVAL,
        decision = SourceSyncEntryDecision.UNDECIDED,
    )
    private fun record(id: String) = SourceRecord(
        syncSourceId = id,
        sourceType = SyncSourceTypeValue.parse("emby"),
        displayName = "source " + id,
    )
}
