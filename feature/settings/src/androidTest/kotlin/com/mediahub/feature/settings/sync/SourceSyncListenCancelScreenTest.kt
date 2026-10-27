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
 * W6-61：「取消这一次接收」按钮的界面证据。
 *
 * W6-36 只做了「等一次发送」，一旦点下去就只能在 accept 预算（缺省 15 秒）里干等——
 * 想放弃只能「停止监听」再重开（那会把端口和 TLS 指纹一起换掉）。W6-61 补上第三条路：
 * 只取消这一次等待，监听不关、端口不变、状态回到「正在监听」，立刻能再等一次。
 *
 * 逻辑侧（把手怎么解阻塞、状态怎么回 Listening、不留码）已由 core:common 的
 * SourceSyncListenerCancelTest 与纯 JVM 的 SourceSyncListenLauncherTest 钉住；
 * 这里只钉界面：**真有一个能按的按钮**、只在接收中可点、按下去真把回调打到 ViewModel。
 *
 * 为什么每条断言都先 performScrollTo()：页面根节点是 verticalScroll 的 Column，
 * 监听卡与其它两个接收/发送卡一样刻意放在页尾（首屏保留「选择文件 / 预览」主流程），
 * 真机视口里它在首屏之外——语义树里有节点但 assertIsDisplayed() 为假、点击也落不到。
 *
 * 按钮纪律：与「取消接收」「取消发送」一致——常驻、**禁用而不是隐藏**，
 * 所以这里能断「没在接收时存在但不可用」，不必按状态分支查找节点。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncListenCancelScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCancelButtonIsThereButDisabledWhenNothingIsBeingWaitedFor() {
        render(listenState = SourceSyncListenState.Listening(port = 45_678, tls = false))

        val cancel = composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo()
        cancel.assertIsDisplayed()
        // 常驻 + 禁用：不靠"隐藏"避免误点，界面结构在四种状态下一模一样。
        cancel.assertIsNotEnabled()
        cancel.assertTextContains(SourceSyncListenCopy.CANCEL_RECEIVE)
    }

    @Test
    fun theCancelButtonBecomesUsableOnlyWhileReceiving() {
        render(listenState = SourceSyncListenState.Receiving(port = 45_678))

        val cancel = composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo()
        cancel.assertIsEnabled()
        // 忙碌时「等一次发送」本身要被关掉，避免重复点出两条等待。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIVE).assertIsNotEnabled()
        // 接收中也不许「开始监听」：端口已经占着，再点必然失败。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsNotEnabled()
    }

    @Test
    fun tappingCancelWhileReceivingCallsBackExactlyOnce() {
        var cancelled = 0
        render(listenState = SourceSyncListenState.Receiving(port = 45_678), onListenCancel = { cancelled++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun cancelStaysDisabledAfterAReceivedEnvelope() {
        // 收下之后状态是 Received（不是 Receiving）：「这一次」已经结束，没有可取消的东西。
        render(
            listenState = SourceSyncListenState.Received(
                port = 45_678,
                deviceId = "device-a",
                fileName = SourceSyncListenerController.LISTEN_FILE_NAME,
            ),
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_STATUS)
            .assertTextContains("已收下信封", substring = true)
    }

    @Test
    fun cancelStaysDisabledAfterAFailedWait() {
        render(listenState = SourceSyncListenState.Failed(port = 45_678, code = "peer-timeout", detail = null))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_STATUS)
            .assertTextContains("失败：peer-timeout", substring = true)
    }

    @Test
    fun theCancelButtonSitsAtThePageTailAndTheMainFlowStaysOnTheFirstScreen() {
        render(listenState = SourceSyncListenState.Receiving(port = 45_678))

        // 不滚动：选择文件 / 预览两个按钮必须仍在首屏（W6-41 的回归就是被这个断言守住的）。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        // 取消按钮在页尾，要滚动才看得见；滚下去之后再回到首屏仍然可以。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_CANCEL).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        listenState: SourceSyncListenState = SourceSyncListenState.Idle,
        onListenCancel: () -> Unit = {},
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
                    listenState = listenState,
                    onListenCancel = onListenCancel,
                )
            }
        }
    }
}
