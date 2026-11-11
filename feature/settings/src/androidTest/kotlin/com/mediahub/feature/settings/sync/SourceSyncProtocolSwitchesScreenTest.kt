package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-48：同步页两个协议开关的界面证据——发送端的「投递确认」（sync.send.delivery）与
 * 监听端的「回 0x04 回执」（sync.listen.receipt）。
 *
 * 为什么单开一个文件：W6-44 的 [SourceSyncSectionsScreenTest] 已经钉住三张卡的闸门与结果行，
 * 那两个开关在 W6-46 / W6-40 只有控制器与协议层证据、界面上根本没有控件。本文件只证明界面
 * 这件事：默认关、能勾、勾了会随表单交出去、回执优先时投递确认点不动。
 *
 * 与 W6-44 同一条滚动纪律：两个开关都在页尾（SourceSyncImportScreen.kt 里三张卡刻意放在
 * Column 末尾），任何断言前都必须先 performScrollTo()；只有最后一个用例故意不滚动，
 * 它要证明的正是「首屏主流程没被顶下去」。
 *
 * 断言只依赖 [SourceSyncImportTags] 的稳定标签与 SourceSyncSendXxxCopy 的固定文案，
 * 不依赖布局位置。设备证据由 CI 的 visual-instrumentation（API 32 / 36）提供。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncProtocolSwitchesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------ 发送：投递确认

    @Test
    fun bothProtocolSwitchesStartUnchecked() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIPT).performScrollTo().assertIsOff()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_DELIVERY).performScrollTo().assertIsOff()
    }

    @Test
    fun theDeliverySwitchCanBeToggledAndIsForwarded() {
        var captured: SourceSyncSendForm? = null
        render(onSend = { form -> captured = form })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("40404")
        val delivery = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_DELIVERY).performScrollTo()
        delivery.assertIsOff()
        delivery.performClick()
        delivery.assertIsOn()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo().performClick()

        val form = captured
        assertTrue("点了发送就应该把表单交给控制器", form != null)
        assertTrue("勾了投递确认就必须随表单一起来", form!!.delivery)
        assertFalse("投递确认与回执是两件事，回执应当还留在关着", form!!.receipt)
    }

    @Test
    fun theDeliverySwitchIsDisabledWhileTheReceiptIsAwaited() {
        render()

        val receipt = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_RECEIPT).performScrollTo()
        val delivery = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_DELIVERY).performScrollTo()
        delivery.assertIsEnabled()

        // 回执优先：勾着等回执时投递确认不起作用，界面得让它点不动（而不是勾了也白勾）。
        receipt.performClick()
        delivery.assertIsNotEnabled()

        receipt.performClick()
        delivery.assertIsEnabled()
    }

    @Test
    fun aFormSubmittedWithTheReceiptOnNeverCarriesTheDelivery() {
        var captured: SourceSyncSendForm? = null
        render(onSend = { form -> captured = form })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("40404")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_RECEIPT).performScrollTo().performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo().performClick()

        val form = captured
        assertTrue("点了发送就应该把表单交给控制器", form != null)
        assertTrue("勾着等回执", form!!.receipt)
        assertFalse("回执优先，投递确认不该被带上", form!!.delivery)
    }

    // ------------------------------------------------------------ 监听：回 0x04 回执

    @Test
    fun theListenReceiptSwitchCanBeToggledBothWays() {
        render()

        val receipt = composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIPT).performScrollTo()
        receipt.assertIsOff()
        receipt.performClick()
        receipt.assertIsOn()
        receipt.performClick()
        receipt.assertIsOff()
    }

    @Test
    fun theListenReceiptSwitchIsForwardedWithTheForm() {
        var captured: SourceSyncListenForm? = null
        render(onListenStart = { form -> captured = form })

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIPT).performScrollTo().performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).performScrollTo().performClick()

        val form = captured
        assertTrue("点了开始监听就应该把表单交给控制器", form != null)
        assertTrue("回执开关必须随表单一起来", form!!.receipt)
        // 端口留空 = 让系统分配，回执开关不该改变这条默认姿势。
        assertTrue(
            "端口留空时应当是系统分配",
            form!!.portNumber == SourceSyncListenForm.SYSTEM_ASSIGNED_PORT,
        )
    }

    @Test
    fun theListenReceiptSwitchIsFrozenWhileListening() {
        render(listenState = SourceSyncListenState.Listening(port = 40404, tls = false))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIPT).performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()
    }

    // ---------------------------------------------------------- 页尾位置与首屏不变式

    @Test
    fun bothSwitchesSitAtThePageTailAndTheMainFlowStaysOnTheFirstScreen() {
        render()

        // 不滚动：选择文件 / 预览这两个首屏主流程按钮必须仍然可见。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        // 两个开关都在页尾，要滚动才看得见——这是 W6-41 起的纪律，不是布局缺陷。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIPT).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_DELIVERY).performScrollTo()
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        state: SourceSyncImportState = SourceSyncImportState(),
        listenState: SourceSyncListenState = SourceSyncListenState.Idle,
        sendState: SourceSyncSendState = SourceSyncSendState.Idle,
        onListenStart: (SourceSyncListenForm) -> Unit = {},
        onSend: (SourceSyncSendForm) -> Unit = {},
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
                    listenState = listenState,
                    listenTls = NO_TLS,
                    onListenStart = onListenStart,
                    onListenStop = {},
                    onListenReceive = {},
                    sendState = sendState,
                    onSend = onSend,
                )
            }
        }
    }

    private companion object {
        val NO_TLS: SourceSyncListenTlsAvailability =
            SourceSyncListenTlsAvailability(false, SourceSyncListenCopy.TLS_UNAVAILABLE)
    }
}
