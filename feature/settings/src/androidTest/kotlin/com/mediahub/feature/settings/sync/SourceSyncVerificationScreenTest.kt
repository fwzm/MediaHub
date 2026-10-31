package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-H 结果页「补录并验证」的交互证据（androidTest / instrumentation）。
 *
 * 本机不跑模拟器：这些用例只做编译校验，真机/模拟器执行证据由 CI 的 visual-instrumentation job 提供
 * （模块清单含 :feature:settings，见 docs/android-sync-ui.md）。断言只依赖 SourceSyncImportTags
 * 的稳定标签与 SourceVerificationCopy 的固定文案，不依赖布局位置。
 *
 * 结果卡与逐源验证行都在 verticalScroll 列的最底部：断言「看得见」或点击之前必须先 performScrollTo()，
 * 否则在真模拟器视口里会假红（同源教训见 SourceSyncImportScreenTest 的类注释）。
 * 对话框是 AlertDialog overlay，其内部节点不需要滚动。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncVerificationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyImportedSourceGetsAVerifyButtonAndAStateLine() {
        render(appliedState(), verificationState())

        composeRule.onNodeWithTag(SourceSyncImportTags.verifyRow("srv-1"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.verifyRow("srv-2"))
            .performScrollTo()
            .assertIsDisplayed()
        verifyButton("srv-1").performScrollTo().assertIsDisplayed().assertIsEnabled()
        verifyButton("srv-2").performScrollTo().assertIsDisplayed()
        stateLine("srv-1").assertTextEquals(SourceVerificationCopy.NEEDS_CREDENTIALS_TEXT)
        // 结果页的固定免责句必须还在：导入 != 已登录。
        composeRule.onNodeWithTag(SourceSyncImportTags.RESULT).performScrollTo().assertIsDisplayed()
        // VERIFY_STATE 每个来源一个（这里两个来源）⇒ 不能用 onNodeWithTag（会因多匹配报错）。
        composeRule.onAllNodesWithTag(SourceSyncImportTags.VERIFY_STATE).assertCountEquals(2)
    }

    @Test
    fun tappingVerifyReportsTheServerIdAndOpensTheDialog() {
        var requested: String? = null
        render(
            appliedState(),
            verificationState(dialogServerId = "srv-1"),
            onVerifyRequest = { requested = it },
        )

        verifyButton("srv-1").performScrollTo().performClick()

        assertEquals("srv-1", requested)
        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_INPUT).assertIsDisplayed()
    }

    @Test
    fun thePasswordFieldIsMaskedAndConfirmNeedsInput() {
        render(appliedState(), verificationState(dialogServerId = "srv-1"))

        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_INPUT).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_CONFIRM).assertIsNotEnabled()

        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_INPUT)
            .performTextInput("s3cret-Pa55word")

        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_CONFIRM).assertIsEnabled()
    }

    @Test
    fun confirmingReportsTheServerIdAndTheTypedSecret() {
        var confirmedServerId: String? = null
        var confirmedSecret: String? = null
        render(
            appliedState(),
            verificationState(dialogServerId = "srv-2"),
            onVerifyConfirm = { serverId, secret ->
                confirmedServerId = serverId
                confirmedSecret = secret
            },
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_INPUT)
            .performTextInput("s3cret-Pa55word")
        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_CONFIRM).performClick()

        assertEquals("srv-2", confirmedServerId)
        assertEquals("s3cret-Pa55word", confirmedSecret)
    }

    @Test
    fun cancellingDismissesWithoutConfirming() {
        var dismissals = 0
        var confirms = 0
        render(
            appliedState(),
            verificationState(dialogServerId = "srv-1"),
            onVerifyDismiss = { dismissals++ },
            onVerifyConfirm = { _, _ -> confirms++ },
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.VERIFY_CANCEL).performClick()

        assertEquals(1, dismissals)
        assertEquals("取消不得提交任何凭据", 0, confirms)
    }

    @Test
    fun theThreeVerificationStatesRenderThreeDifferentLines() {
        val state = verificationState(
            outcomes = listOf(
                outcome("srv-1", SourceVerificationState.VERIFIED, SourceVerificationReasons.VERIFIED),
                outcome("srv-2", SourceVerificationState.FAILED, SourceVerificationReasons.AUTH_FAILED),
            ),
        )
        render(appliedState(), state)

        stateLine("srv-1").performScrollTo().assertTextEquals(SourceVerificationCopy.VERIFIED_TEXT)
        stateLine("srv-2").performScrollTo().assertTextEquals(
            SourceVerificationCopy.FAILED_PREFIX + SourceVerificationCopy.AUTH_FAILED_MESSAGE,
        )
    }

    // ---------------------------------------------------------------- 夹具

    private fun render(
        state: SourceSyncImportState,
        verification: SourceVerificationViewState = SourceVerificationViewState(),
        onVerifyRequest: (String) -> Unit = {},
        onVerifyDismiss: () -> Unit = {},
        onVerifyConfirm: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                SourceSyncImportScreen(
                    state = state,
                    onPick = {},
                    onPreview = {},
                    onDecide = { _, _ -> },
                    onApply = {},
                    onBack = {},
                    verification = verification,
                    onVerifyRequest = onVerifyRequest,
                    onVerifyDismiss = onVerifyDismiss,
                    onVerifyConfirm = onVerifyConfirm,
                )
            }
        }
    }

    private fun verifyButton(serverId: String) = composeRule.onNode(
        hasTestTag(SourceSyncImportTags.VERIFY) and
            hasAnyAncestor(hasTestTag(SourceSyncImportTags.verifyRow(serverId))),
    )

    private fun stateLine(serverId: String) = composeRule.onNode(
        hasTestTag(SourceSyncImportTags.VERIFY_STATE) and
            hasAnyAncestor(hasTestTag(SourceSyncImportTags.verifyRow(serverId))),
    )

    private fun appliedState(): SourceSyncImportState = SourceSyncImportState(
        status = SourceSyncImportStatus.APPLIED,
        fileName = "device-a.json",
        envelopeJson = "{}",
        applied = SourceSyncApplySummary(
            appliedCount = 2,
            requiresApprovalCount = 0,
            ignoredCount = 0,
            unsupportedCount = 0,
            failedCount = 0,
            appliedSources = listOf(
                SourceSyncAppliedSource(
                    serverId = "srv-1",
                    title = "客厅 Emby",
                    sourceTypeRaw = "emby",
                    syncSourceId = "local-a",
                ),
                SourceSyncAppliedSource(
                    serverId = "srv-2",
                    title = "书房 Jellyfin",
                    sourceTypeRaw = "jellyfin",
                    syncSourceId = "local-b",
                ),
            ),
        ),
    )

    private fun verificationState(
        outcomes: List<SourceVerificationOutcome> = emptyList(),
        dialogServerId: String? = null,
    ): SourceVerificationViewState = SourceVerificationViewState(
        sources = listOf(
            SourceVerificationSource(
                serverId = "srv-1",
                title = "客厅 Emby",
                serverType = ServerType.EMBY,
                accountScope = "alice",
                syncSourceId = "local-a",
            ),
            SourceVerificationSource(
                serverId = "srv-2",
                title = "书房 Jellyfin",
                serverType = ServerType.JELLYFIN,
                accountScope = "bob",
                syncSourceId = "local-b",
            ),
        ),
        outcomes = outcomes.associateBy { it.serverId },
        dialogServerId = dialogServerId,
    )

    private fun outcome(
        serverId: String,
        state: SourceVerificationState,
        reason: String,
    ): SourceVerificationOutcome = SourceVerificationOutcome(
        serverId = serverId,
        state = state,
        reason = reason,
        message = SourceVerificationCopy.reasonText(reason),
        checkedAtEpochMs = 1_760_000_000_000L,
    )
}
