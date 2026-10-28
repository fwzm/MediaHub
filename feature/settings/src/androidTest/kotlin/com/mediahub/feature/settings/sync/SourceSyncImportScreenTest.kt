package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-F 导入页的交互证据（androidTest / instrumentation）。
 *
 * 为什么在 androidTest：本仓的 Compose UI 测试依赖（ui-test-junit4）只在 androidTest 配置里，
 * 且 CI 的 visual-instrumentation job 会在 API 32 / API 36 两个模拟器上真跑
 * connectedDebugAndroidTest（模块清单见 windows/../run-visual-validation.sh 的 Android 侧清单：
 * run-visual-validation.sh 的 module 清单里含 :feature:settings）。
 *
 * 本机不跑模拟器：这些用例只做编译校验，执行证据由 CI 提供（见 docs/android-sync-ui.md）。
 * 断言只依赖 SourceSyncImportTags 里的稳定标签与固定文案，不依赖布局位置。
 *
 * 页面根节点是 verticalScroll 的 Column（SourceSyncImportScreen.kt:155-160）：在真模拟器视口里
 * 靠下的条目/结果卡并不在首屏，语义树里有节点但 assertIsDisplayed() 为假、点击也落不到。
 * 所以凡是断言「看得见」或要点击的节点，一律先 performScrollTo()。
 * 这条来自 API 32 上 4 个用例假红（2026-10-24，run 40028318723）的真实教训。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncImportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun thePickerAndPreviewButtonsAreOnScreenFromTheStart() {
        render(SourceSyncImportState())

        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsNotEnabled()
    }

    @Test
    fun pickingAFileReportsTheClickWithoutAnyEnvelopeYet() {
        var picks = 0
        render(SourceSyncImportState(), onPick = { picks++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).performClick()

        assertEquals(1, picks)
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsNotEnabled()
    }

    @Test
    fun previewBecomesAvailableOnceAFileIsLoaded() {
        var previews = 0
        val state = SourceSyncImportState(
            status = SourceSyncImportStatus.READY_TO_PREVIEW,
            fileName = "device-a.json",
            envelopeJson = "{}",
        )
        render(state, onPreview = { previews++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsEnabled().performClick()

        assertEquals(1, previews)
        composeRule.onNodeWithText("已选：device-a.json").assertIsDisplayed()
    }

    @Test
    fun thePreviewShowsEveryBucketAndTheSummaryLine() {
        val state = previewedState()
        render(state)

        composeRule.onNodeWithText("可导入（2）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("需确认（1）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("已忽略（1）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("暂不支持（1）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("共 5 条：可导入 2 · 需确认 1 · 已忽略 1 · 暂不支持 1")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theApplyButtonStaysDisabledUntilSomethingIsAllowed() {
        render(previewedState())

        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY).assertIsNotEnabled()
    }

    @Test
    fun allowingOneEntryEnablesApplyAndReportsTheKey() {
        var decidedKey: String? = null
        var decidedValue: SourceSyncEntryDecision? = null
        val state = previewedState().withAllow("sync-1")
        render(state, onDecide = { key, decision ->
            decidedKey = key
            decidedValue = decision
        })

        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY).performScrollTo().assertIsEnabled()
        allowButton("sync-1").performScrollTo().assertIsEnabled().performClick()

        assertEquals("sync-1", decidedKey)
        assertEquals(SourceSyncEntryDecision.ALLOW, decidedValue)
    }

    @Test
    fun theSkipButtonReportsASkipForThatEntryOnly() {
        var decidedKey: String? = null
        var decidedValue: SourceSyncEntryDecision? = null
        render(previewedState()) { key, decision ->
            decidedKey = key
            decidedValue = decision
        }

        skipButton("sync-2").performScrollTo().performClick()

        assertEquals("sync-2", decidedKey)
        assertEquals(SourceSyncEntryDecision.SKIP, decidedValue)
    }

    @Test
    fun applyingReportsTheClickWhenSomethingIsAllowed() {
        var applies = 0
        render(previewedState().withAllow("sync-1"), onApply = { applies++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY).performScrollTo().performClick()

        assertEquals(1, applies)
    }

    @Test
    fun theUnknownTypeEntryCanNeverBeAllowed() {
        render(previewedState())

        allowButton("sync-quantum").assertIsNotEnabled()
        skipButton("sync-quantum").assertIsNotEnabled()
        // 该串同时是「暂不支持」桶的说明，所以断言限定在这张条目卡片内（onNodeWithText 会因重复匹配报错）。
        stateText("sync-quantum").assertTextEquals(SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED)
    }

    @Test
    fun theConflictEntryInTheApprovalBucketCannotBeAllowedEither() {
        render(previewedState())

        allowButton("sync-conflict").assertIsNotEnabled()
        stateText("sync-conflict").assertTextEquals(SourceSyncImportCopy.NEVER_ALLOW_APPROVAL)
    }

    @Test
    fun everyEntryShowsTheStateItIsIn() {
        render(previewedState().withAllow("sync-1"))

        stateText("sync-1").assertTextEquals(SourceSyncImportCopy.ALLOWED_STATE)
        stateText("sync-2").assertTextEquals(SourceSyncImportCopy.ALLOW_RISK)
        stateText("sync-quantum").assertTextEquals(SourceSyncImportCopy.NEVER_ALLOW_UNSUPPORTED)
        stateText("sync-conflict").assertTextEquals(SourceSyncImportCopy.NEVER_ALLOW_APPROVAL)
    }

    @Test
    fun aSkippedEntrySaysItWillNotBeImported() {
        val state = previewedState().withSkip("sync-2")
        render(state)

        stateText("sync-2").assertTextEquals(SourceSyncImportCopy.SKIPPED_STATE)
    }

    @Test
    fun theResultCardSeparatesThePlanFromTheReceiptAndCarriesTheDisclaimer() {
        // W6-82：第一行改成「计划」（来自预览的分桶），第二行才是「结果」（来自回执）。
        // 旧断言钉的是被退役的「已导入 N · 需确认 N · …」那一行，CI 上一整轮假红。
        val summary = SourceSyncApplySummary(
            appliedCount = 1,
            requiresApprovalCount = 1,
            ignoredCount = 1,
            unsupportedCount = 1,
            failedCount = 0,
            skippedCount = 1,
            withheldCount = 1,
            allowedCount = 1,
            plannedCount = 4,
            appliedTitles = listOf("客厅 Emby"),
        )
        val state = previewedState().withAllow("sync-1").copy(
            status = SourceSyncImportStatus.APPLIED,
            applied = summary,
        )
        render(state)

        composeRule.onNodeWithTag(SourceSyncImportTags.RESULT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY_PLAN)
            .performScrollTo()
            .assertTextEquals(SourceSyncImportCopy.planLine(summary))
        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY_VERDICT)
            .performScrollTo()
            .assertTextEquals(SourceSyncImportCopy.resultLine(summary))
        composeRule.onNodeWithText("- 客厅 Emby").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SourceSyncImportCopy.DISCLAIMER).performScrollTo().assertIsDisplayed()
        // 回执里没有失败条目 ⇒ 逐条失败区整块不出现（0 条不占版面）。
        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY_REFUSED).assertDoesNotExist()
    }

    @Test
    fun everyRefusedEntryIsListedWithItsIdentityActionAndReason() {
        val summary = SourceSyncApplySummary(
            appliedCount = 0,
            requiresApprovalCount = 0,
            ignoredCount = 0,
            unsupportedCount = 0,
            failedCount = 2,
            allowedCount = 2,
            plannedCount = 2,
            refusedEntries = listOf(
                SourceSyncRefusedEntry(
                    syncSourceId = "sync-1",
                    title = "客厅 Emby",
                    action = SourceSyncRefusedAction.WRITE,
                    reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE,
                ),
                SourceSyncRefusedEntry(
                    syncSourceId = "sync-2",
                    title = "书房 Emby",
                    action = SourceSyncRefusedAction.DELETE,
                    reason = "something-new:42",
                ),
            ),
        )
        val state = previewedState().copy(
            status = SourceSyncImportStatus.APPLIED,
            applied = summary,
        )
        render(state)

        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY_REFUSED)
            .performScrollTo()
            .assertTextEquals("未写入 2 条（逐条原因）")
        summary.refusedEntries.forEach { entry ->
            composeRule.onNodeWithText(SourceSyncImportCopy.refusalLine(entry))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun theBlockedCardExplainsWhyNothingWasApplied() {
        val state = SourceSyncImportState(
            status = SourceSyncImportStatus.BLOCKED,
            fileName = "device-a.json",
            envelopeJson = "{}",
            blockedReason = SourceSyncImportCopy.NOTHING_ALLOWED,
        )
        render(state)

        composeRule.onNodeWithTag(SourceSyncImportTags.BLOCKED).assertIsDisplayed()
        composeRule.onNodeWithText(SourceSyncImportCopy.blockedText(SourceSyncImportCopy.NOTHING_ALLOWED))
            .assertIsDisplayed()
    }

    @Test
    fun aRejectedEnvelopeBlockCardShowsTheReasonCode() {
        val state = SourceSyncImportState(
            status = SourceSyncImportStatus.BLOCKED,
            blockedReason = "malformed-json",
            blockedMessage = "sources is not an array",
        )
        render(state)

        composeRule.onNodeWithTag(SourceSyncImportTags.BLOCKED).assertIsDisplayed()
        composeRule.onNodeWithText("sources is not an array").assertIsDisplayed()
    }

    @Test
    fun theWholeFlowLivesOnOneScrollableScreenWithoutASecondPage() {
        render(previewedState())

        // 选择文件 / 预览 在首屏；应用按钮与条目在同一个滚动页面里，滚一下就到 —— 没有二级页面。
        composeRule.onNodeWithTag(SourceSyncImportTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.APPLY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.entry("sync-1"))
            .performScrollTo()
            .assertIsDisplayed()
        // 滚回去还能看到首屏按钮 ⇒ 全程没有页面跳转。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).performScrollTo().assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 夹具

    private fun render(
        state: SourceSyncImportState,
        onPick: () -> Unit = {},
        onPreview: () -> Unit = {},
        onApply: () -> Unit = {},
        onDecide: (String, SourceSyncEntryDecision) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                SourceSyncImportScreen(
                    state = state,
                    onPick = onPick,
                    onPreview = onPreview,
                    onDecide = onDecide,
                    onApply = onApply,
                    onBack = {},
                )
            }
        }
    }

    private fun allowButton(key: String) = composeRule.onNode(
        hasTestTag(SourceSyncImportTags.ENTRY_ALLOW) and
            hasAnyAncestor(hasTestTag(SourceSyncImportTags.entry(key))),
    )

    private fun skipButton(key: String) = composeRule.onNode(
        hasTestTag(SourceSyncImportTags.ENTRY_SKIP) and
            hasAnyAncestor(hasTestTag(SourceSyncImportTags.entry(key))),
    )

    private fun stateText(key: String) = composeRule.onNode(
        hasTestTag(SourceSyncImportTags.ENTRY_STATE) and
            hasAnyAncestor(hasTestTag(SourceSyncImportTags.entry(key))),
    )

    private fun previewedState(): SourceSyncImportState {
        val plan = SourceSyncPlan(
            listOf(
                candidate("sync-1", SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate("sync-2", SourceSyncAction.CREATE, SourceSyncReasons.NEW_SOURCE),
                candidate("sync-conflict", SourceSyncAction.CONFLICT, SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT),
                candidate("sync-known", SourceSyncAction.NO_CHANGE, SourceSyncReasons.UP_TO_DATE),
                candidate("sync-quantum", SourceSyncAction.UNSUPPORTED, SourceSyncReasons.UNKNOWN_SOURCE_TYPE, known = false),
            ),
        )
        return SourceSyncImportState(
            status = SourceSyncImportStatus.PREVIEWED,
            fileName = "device-a.json",
            envelopeJson = "{}",
            preview = SourceSyncImportLogic.previewView("device-a", "snap-1", plan),
        )
    }

    private fun candidate(
        id: String,
        action: SourceSyncAction,
        reason: String,
        known: Boolean = true,
    ) = SourceSyncCandidate(
        action = action,
        reason = reason,
        syncSourceId = id,
        sourceType = if (known) "emby" else "quantum-nas",
        isKnownSourceType = known,
        displayName = "来源 " + id,
    )

    private fun SourceSyncImportState.withAllow(key: String): SourceSyncImportState =
        copy(preview = preview?.withDecision(key, SourceSyncEntryDecision.ALLOW))

    private fun SourceSyncImportState.withSkip(key: String): SourceSyncImportState =
        copy(preview = preview?.withDecision(key, SourceSyncEntryDecision.SKIP))
}
