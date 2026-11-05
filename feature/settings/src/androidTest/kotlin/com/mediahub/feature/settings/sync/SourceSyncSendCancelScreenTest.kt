package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-49：「取消发送」按钮的界面证据。
 *
 * 为什么单独一个文件：W6-42 明说界面没有取消入口（见 docs/android-source-sync-transfer.md §23.8），
 * W6-49 把它补上——只在 [SourceSyncSendState.Sending] 时可用，取消回 Idle 且不显示失败码。
 * 逻辑侧已由 SourceSyncSendLauncherTest（纯 JVM）钉住，这里只钉「界面上真有一个能按的按钮、
 * 按下去真会把回调打到 ViewModel，且其它状态按不动」。
 *
 * 为什么每条断言都先 performScrollTo()：页面根节点是 verticalScroll 的 Column
 * （SourceSyncImportScreen.kt:253），发送卡刻意放在页尾（:419-422 的注释），真机视口里
 * 它在首屏之外——语义树里有节点但 assertIsDisplayed() 为假、点击也落不到。
 * 只有最后一个用例（主流程仍在首屏）**故意不滚动**，它要证明的正是「首屏还在」。
 *
 * 按钮纪律：取消按钮常驻、**禁用而不是隐藏**（SendSection KDoc，SourceSyncImportScreen.kt:889-891），
 * 所以这里能断「Idle 时存在但不可用」，而不必分支查找节点。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncSendCancelScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCancelButtonIsThereButDisabledBeforeAnythingIsSent() {
        render(sendState = SourceSyncSendState.Idle)

        val cancel = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo()
        cancel.assertIsDisplayed()
        // 常驻 + 禁用：不靠"隐藏"来避免误点，界面结构在四种状态下都一样。
        cancel.assertIsNotEnabled()
        cancel.assertTextContains(SourceSyncSendCopy.CANCEL)
    }

    @Test
    fun theCancelButtonBecomesUsableWhileSending() {
        render(sendState = SourceSyncSendState.Sending)

        val cancel = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo()
        cancel.assertIsDisplayed()
        cancel.assertIsEnabled()
        // 忙碌时发送按钮本身要被关掉，避免重复点击。
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsNotEnabled()
    }

    @Test
    fun tappingCancelWhileSendingCallsBackExactlyOnce() {
        var cancelled = 0
        render(sendState = SourceSyncSendState.Sending, onSendCancel = { cancelled++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun aSuccessfulSendLeavesTheCancelButtonDisabled() {
        render(
            sendState = SourceSyncSendState.Sent(bytes = 665, frames = 2, receipt = false, sourceCount = 1),
        )

        val cancel = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo()
        cancel.assertIsNotEnabled()
        // 成功之后不该出现"取消"这种语义误导：结果行只说已发送。
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_STATE)
            .assertTextContains("已发送 665 字节", substring = true)
    }

    @Test
    fun aFailedSendAlsoLeavesTheCancelButtonDisabled() {
        render(sendState = SourceSyncSendState.Failed(code = "peer-timeout"))

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_STATE)
            .assertTextContains("发送失败：peer-timeout", substring = true)
    }

    @Test
    fun theCancelButtonSitsAtThePageTailAndTheMainFlowStaysOnTheFirstScreen() {
        render(sendState = SourceSyncSendState.Sending)

        // 不滚动：选择文件 / 预览两个按钮必须仍在首屏。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        // 取消按钮在页尾，要滚动才看得见；滚下去之后再回到首屏仍然可以。
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_CANCEL).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        sendState: SourceSyncSendState = SourceSyncSendState.Idle,
        onSendCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                SourceSyncImportScreen(
                    state = SourceSyncImportState(),
                    onPick = {},
                    onPreview = {},
                    onDecide = { _, _ -> },
                    onApply = {},
                    onBack = {},
                    sendState = sendState,
                    onSend = {},
                    onSendCancel = onSendCancel,
                )
            }
        }
    }
}
