package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCandidate
import com.mediahub.core.common.sync.SourceSyncPlan
import com.mediahub.core.common.sync.SourceSyncReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-44：同步页三张卡（接收 / 监听 / 发送）的界面证据。
 *
 * 为什么要单独一个文件：W6-27 / W6-36 / W6-42 往同步页页尾加了三张卡，但那三张卡的行为
 * （端口闸门、TLS 指纹闸门、口令闸门、失败码显示）此前只有 JVM 单测与 composeRule 之外的
 * 控制器测试钉住，没有任何 instrumentation 证据。CI 的 visual-instrumentation job（API 32 / 36）
 * 会真跑本文件的用例，设备证据由 CI 提供。
 *
 * 为什么每个用例都先 performScrollTo()：页面根节点是 verticalScroll 的 Column
 * （SourceSyncImportScreen.kt:253），三张卡刻意放在页尾（:419-422 的注释），
 * 在真机视口里它们在首屏之外——语义树里有节点但 assertIsDisplayed() 为假、点击也落不到。
 * 只有最后一个用例（主流程不被挤出首屏）**故意不滚动**，它要证明的正是「首屏还在」。
 *
 * 断言只依赖 SourceSyncImportTags 的稳定标签与 SourceSyncXxxCopy 的固定文案，不依赖布局位置。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncSectionsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------ 接收

    @Test
    fun receiveSectionStartsOnTheLoopbackHostAndWaitsForAPort() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_HOST).performScrollTo()
            .assertTextContains(SourceSyncReceiveForm.DEFAULT_HOST, substring = true)
        // 端口是必填项：没填时按钮禁用，并且界面要说清楚为什么。
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncReceiveCopy.PORT_REQUIRED).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun receivePortGateAcceptsOnlyRealPorts() {
        render()

        val port = composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_PORT)
        port.performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).assertIsNotEnabled()

        port.performTextReplacement("70000")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).assertIsNotEnabled()

        port.performTextReplacement("8999")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).assertIsEnabled()
    }

    @Test
    fun nonLoopbackReceiveHostIsForwardedAsTyped() {
        var received: SourceSyncReceiveForm? = null
        render(onReceive = { received = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_HOST).performScrollTo()
            .performTextReplacement("192.168.1.5")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_PORT).performScrollTo()
            .performTextReplacement("8999")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).performScrollTo()
            .performClick()

        val form = received
        assertNotNull("接收按钮没把表单交出去", form)
        // 非回环地址**故意不在表单层拦**（SourceSyncReceiveController.kt:88-90）：拒绝是传输客户端的
        // not-loopback 判定，表单只负责原样转交，否则同一个错误会有两个来源、两种说法。
        assertEquals("192.168.1.5", form!!.host)
        assertEquals(8999, form.portNumber)
    }

    // ------------------------------------------- W6-52b 接收卡的「允许非回环单播」开关

    @Test
    fun theReceiveCardOffersACrossMachineSwitch() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ALLOW_HOST).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // 说明必须写清「只收单播字面量、码仍是 not-loopback」，否则用户会以为是域名输入框。
        // 发送卡与接收卡用的是同一句说明（同一件事的两个方向），所以整页语义树里必然命中 2 个节点：
        // 这里只断言「两处都在」，单节点断言会直接报 Expected exactly '1' node but found '2'
        // （第一次 CI 实跑就红在这里——本机 JVM 测试看不到两张卡同时在场）。
        composeRule.onAllNodesWithText(SourceSyncReceiveCopy.ALLOW_HOST_HINT).assertCountEquals(2)
        composeRule.onNodeWithText(SourceSyncReceiveCopy.ALLOW_HOST_LABEL).assertIsDisplayed()
    }

    @Test
    fun theReceiveFormCarriesTheCrossMachineSwitchVerbatim() {
        var received: SourceSyncReceiveForm? = null
        render(onReceive = { received = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_PORT).performScrollTo()
            .performTextReplacement("8999")
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ALLOW_HOST).performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).performScrollTo()
            .performClick()

        val form = received
        assertNotNull("接收按钮没把表单交出去", form)
        assertTrue("点了开关就必须原样带过去", form!!.allowExplicitHost)
        assertEquals(8999, form.portNumber)
    }

    @Test
    fun aNotLoopbackFailureIsShownWithItsStableCode() {
        render(receiveState = SourceSyncReceiveState.Failed("not-loopback", null))

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_STATE).performScrollTo()
            .assertTextEquals("接收失败：not-loopback")
    }

    // ------------------------------------------- W6-58 接收中的「取消接收」

    @Test
    fun theReceiveCardOffersACancelButtonThatIsDisabledWhenIdle() {
        render()

        // 取消是「接收中才存在」的动作：没人接收时按钮在场但点不动（本页纪律是禁用而不是隐藏，
        // tag 与布局保持稳定），文案固定，免得用户把「取消」误当「清空结果」。
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_CANCEL).performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncReceiveCopy.CANCEL).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun whileReceivingTheCancelButtonCallsBackAndTheReceiveButtonIsLocked() {
        var cancelled = 0
        render(receiveState = SourceSyncReceiveState.Receiving, onReceiveCancel = { cancelled++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_CANCEL).performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals("取消必须把回调交出去（关连接由控制器负责）", 1, cancelled)

        // 同一时刻不能「再收一个」：接收按钮锁住，四个输入框也锁住。
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_ACTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_PORT).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_HOST).assertIsNotEnabled()
    }

    @Test
    fun onceTheEnvelopeIsInTheCancelButtonIsLockedAgain() {
        // 收下之后不是「可取消」的时刻：取消不是清空结果的动作（那条路是 clearResult）。
        render(receiveState = SourceSyncReceiveState.Received("windows-a", "windows-listener.envelope.json"))
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_CANCEL).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_STATE).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun afterAFailureTheCancelButtonIsLockedAgain() {
        render(receiveState = SourceSyncReceiveState.Failed("connect-failed", "Connection refused"))
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE_CANCEL).performScrollTo()
            .assertIsNotEnabled()
    }

    // ------------------------------------------------------------------ 监听

    @Test
    fun listenStartsWithASystemAssignedPortAndStopsDisabled() {
        render()

        // 端口留空 = 系统分配（SourceSyncListenForm.portNumber 空串返回 0），所以开始按钮可用。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).performScrollTo()
            .assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_STOP).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_PORT_LINE).assertDoesNotExist()
    }

    @Test
    fun listenTlsIsDisabledAndTheReasonIsShown() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_TLS).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_INFO)
            .assertTextEquals(SourceSyncListenCopy.TLS_UNAVAILABLE)
    }

    @Test
    fun anIllegalListenPortBlocksStarting() {
        render()

        val port = composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_PORT)
        port.performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsNotEnabled()

        port.performTextReplacement("45555")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsEnabled()
    }

    @Test
    fun listeningShowsThePortLineAndEnablesStopAndReceive() {
        render(listenState = SourceSyncListenState.Listening(port = 45678, tls = false))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_PORT_LINE).performScrollTo()
            .assertTextContains("45678", substring = true)
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_STOP).assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_RECEIVE).assertIsEnabled()
        // 已经在监听：端口输入框与开始按钮都锁住，避免"再开一个"。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_PORT).assertIsNotEnabled()
    }

    // ------------------------------------------------- W6-52 绑定地址与「允许非回环单播」开关

    @Test
    fun theListenCardOffersAHostFieldAndAnExplicitHostSwitch() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_HOST).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_ALLOW_HOST).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // 开关的说明必须写清「只收单播字面量、码仍是 not-loopback」，否则用户会以为是域名输入框。
        composeRule.onNodeWithText(SourceSyncListenCopy.ALLOW_HOST_HINT).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(SourceSyncListenCopy.HOST_LABEL).assertIsDisplayed()
    }

    @Test
    fun theListenFormCarriesTheTypedHostAndTheSwitchVerbatim() {
        var listened: SourceSyncListenForm? = null
        render(onListenStart = { listened = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_HOST).performScrollTo()
            .performTextReplacement("192.168.1.5")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_ALLOW_HOST).performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).performScrollTo()
            .performClick()

        val form = listened
        assertNotNull("开始监听没把表单交出去", form)
        // 与 W6-27 的接收卡同一口径：地址与开关只原样转交，判断（是否回环 / 是否单播字面量）
        // 只有一处——core 的 SourceSyncHostAddress（W6-52），否则同一个错误会有两种说法。
        assertEquals("192.168.1.5", form!!.host)
        assertTrue("点了开关就必须原样带过去", form.allowExplicitHost)
    }

    @Test
    fun whileListeningTheHostFieldAndTheSwitchAreLocked() {
        render(listenState = SourceSyncListenState.Listening(port = 45678, tls = false, host = "192.168.1.5"))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_PORT_LINE).performScrollTo()
            .assertTextContains("192.168.1.5:45678", substring = true)
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_HOST).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_ALLOW_HOST).assertIsNotEnabled()
    }

    // ------------------------------------------------- W6-53 对端口令（HMAC 认证）开关

    @Test
    fun theListenCardOffersAnAuthSwitchAndASecretField() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_AUTH).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // 开关没点亮时口令框不可编辑（与发送卡同一口径：避免"填了也没用"的假象）。
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_SECRET).performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncListenCopy.AUTH_HINT).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(SourceSyncListenCopy.AUTH_LABEL).assertIsDisplayed()
    }

    @Test
    fun theListenFormCarriesTheAuthSwitchAndTheSecretVerbatim() {
        var listened: SourceSyncListenForm? = null
        render(onListenStart = { listened = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_AUTH).performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_SECRET).performScrollTo()
            .assertIsEnabled()
            .performTextReplacement("s3cret")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).performScrollTo()
            .performClick()

        val form = listened
        assertNotNull("开始监听没把表单交出去", form)
        // 口令只原样转交给控制器；怎么变成 core 的选项、有没有泄漏，由控制器单测钉住。
        assertTrue("点了开关就必须原样带过去", form!!.auth)
        assertEquals("s3cret", form.secret)
    }

    @Test
    fun whileListeningTheAuthSwitchAndTheSecretAreLocked() {
        render(listenState = SourceSyncListenState.Listening(port = 45678, tls = false))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_AUTH).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_SECRET).assertIsNotEnabled()
    }

    // ------------------------------------------------- W6-54 等待预算（毫秒）旋钮

    @Test
    fun theListenCardOffersABudgetFieldWithItsRangeHint() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_BUDGET).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // 范围与「一个框管两段等待」必须写在界面上，否则用户会把 15000 当成整场传输的总时长。
        composeRule.onNodeWithText(SourceSyncListenCopy.BUDGET_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(SourceSyncListenCopy.BUDGET_HINT).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theListenFormCarriesTheTypedBudgetVerbatim() {
        var listened: SourceSyncListenForm? = null
        render(onListenStart = { listened = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_BUDGET).performScrollTo()
            .performTextReplacement("30000")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).performScrollTo()
            .performClick()

        val form = listened
        assertNotNull("开始监听没把表单交出去", form)
        // 界面只原样转交文本；毫秒解析与范围判定只有一处（SourceSyncListenForm.budgetMillis）。
        assertEquals("30000", form!!.budget)
        assertEquals(30_000, form.budgetMillis)
    }

    @Test
    fun anOutOfRangeBudgetKeepsTheListenButtonDisabled() {
        render()

        val budget = composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_BUDGET).performScrollTo()
        budget.performTextReplacement("99")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsNotEnabled()

        budget.performTextReplacement("12s")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsNotEnabled()

        // 留空等于缺省 15000 毫秒，按钮回到可点。
        budget.performTextReplacement("")
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_START).assertIsEnabled()
    }

    @Test
    fun whileListeningTheBudgetFieldIsLocked() {
        render(listenState = SourceSyncListenState.Listening(port = 45678, tls = false))

        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN_BUDGET).performScrollTo()
            .assertIsNotEnabled()
    }

    // ------------------------------------------------------------------ 发送

    @Test
    fun sendSectionNeedsAPortAndKeepsPinAndSecretDisabled() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncSendCopy.PORT_REQUIRED).performScrollTo()
            .assertIsDisplayed()
        // 两个开关都没开时，指纹框与口令框不可编辑（避免"填了也没用"的假象）。
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PIN).assertIsNotEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_SECRET).assertIsNotEnabled()
    }

    @Test
    fun sendPortGateAcceptsOnlyRealPorts() {
        render()

        val port = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT)
        port.performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsNotEnabled()

        port.performTextReplacement("70000")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsNotEnabled()

        port.performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsEnabled()
    }

    @Test
    fun aValidPortHandsTheSendFormOverExactly() {
        var sent: SourceSyncSendForm? = null
        render(onSend = { sent = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo()
            .performClick()

        val form = sent
        assertNotNull("发送按钮没把表单交出去", form)
        assertEquals(8942, form!!.portNumber)
        assertEquals(SourceSyncSendForm.DEFAULT_HOST, form.hostOrLoopback)
        assertFalse("默认不开 TLS", form.tls)
        assertFalse("默认不开认证", form.auth)
        assertFalse("默认不等回执", form.receipt)
    }

    // ------------------------------------------- W6-52b 发送卡的「允许非回环单播」开关

    @Test
    fun theSendCardOffersACrossMachineSwitch() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ALLOW_HOST).performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // 同接收卡：这句说明两张卡共用，整页里命中 2 个节点（第一次 CI 实跑红在单节点断言上）。
        composeRule.onAllNodesWithText(SourceSyncSendCopy.ALLOW_HOST_HINT).assertCountEquals(2)
        composeRule.onNodeWithText(SourceSyncSendCopy.ALLOW_HOST_LABEL).assertIsDisplayed()
    }

    @Test
    fun theSendFormCarriesTheCrossMachineSwitchVerbatim() {
        var sent: SourceSyncSendForm? = null
        render(onSend = { sent = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_HOST).performScrollTo()
            .performTextReplacement("192.168.1.5")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ALLOW_HOST).performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo()
            .performClick()

        val form = sent
        assertNotNull("发送按钮没把表单交出去", form)
        // 地址与开关都只原样转交；「能不能连」只有 core 的 SourceSyncHostAddress 一处判定。
        assertEquals("192.168.1.5", form!!.hostOrLoopback)
        assertTrue("点了开关就必须原样带过去", form.allowExplicitHost)
    }

    @Test
    fun tlsOnWithoutAUsablePinBlocksSending() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_TLS).performScrollTo().performClick()
        // 开了 TLS，指纹框才可编辑。
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PIN).performScrollTo()
            .assertIsEnabled()
            .performTextReplacement("abc")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncSendCopy.PIN_REQUIRED).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aPinnedFingerprintLetsTheFormSubmit() {
        var sent: SourceSyncSendForm? = null
        render(onSend = { sent = it })
        val pin = "a0".repeat(32)

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_TLS).performScrollTo().performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PIN).performScrollTo()
            .performTextReplacement(pin)
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo()
            .assertIsEnabled()
            .performClick()

        val form = sent
        assertNotNull("发送按钮没把表单交出去", form)
        assertTrue("TLS 开关没进表单", form!!.tls)
        assertEquals(pin, form.fingerprint)
        assertNotNull("64 位 hex 指纹应当可解析", form.fingerprintOrNull)
    }

    @Test
    fun authOnWithoutASecretBlocksSending() {
        render()

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_AUTH).performScrollTo().performClick()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_SECRET).performScrollTo()
            .assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).assertIsNotEnabled()
        composeRule.onNodeWithText(SourceSyncSendCopy.SECRET_REQUIRED).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theSecretFieldIsMaskedAndItsValueIsForwarded() {
        var sent: SourceSyncSendForm? = null
        render(onSend = { sent = it })

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_PORT).performScrollTo()
            .performTextReplacement("8942")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_AUTH).performScrollTo().performClick()

        // 口令必须是密码字段（PasswordVisualTransformation）：语义里有 Password 才算真打码。
        val secret = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_SECRET)
        secret.performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextReplacement("s3cr3t-value")
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_ACTION).performScrollTo()
            .assertIsEnabled()
            .performClick()

        val form = sent
        assertNotNull("发送按钮没把表单交出去", form)
        assertEquals("s3cr3t-value", form!!.secret)
        // 表单的 toString 打码（日志/崩溃报告里不该出现口令原文）。
        assertFalse("toString 泄漏了口令", form.toString().contains("s3cr3t-value"))
    }

    @Test
    fun aSendFailureShowsItsOwnCode() {
        render(sendState = SourceSyncSendState.Failed("peer-timeout", null))

        composeRule.onNodeWithTag(SourceSyncImportTags.SEND_STATE).performScrollTo()
            .assertTextEquals("发送失败：peer-timeout")
    }

    @Test
    fun aSendSuccessSaysWhetherTheReceiptWasAwaited() {
        render(
            sendState = SourceSyncSendState.Sent(
                bytes = 665,
                frames = 2,
                receipt = false,
                sourceCount = 2,
                skippedCount = 0,
            ),
        )

        val line = composeRule.onNodeWithTag(SourceSyncImportTags.SEND_STATE).performScrollTo()
        line.assertTextContains("已发送 665 字节", substring = true)
        // 关着等回执时"成功"只代表字节写完，界面必须这么说，不能让用户以为对端收下了。
        line.assertTextContains("没有等回执", substring = true)
    }

    // -------------------------------------------------------------- 首屏不被挤

    @Test
    fun theThreeSectionCardsNeverPushTheMainFlowOffTheFirstScreen() {
        render()

        // 不滚动：选择文件 / 预览两个按钮必须仍在首屏。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        // 三张卡都在同一页里（页尾），要滚动才看得见。
        composeRule.onNodeWithTag(SourceSyncImportTags.RECEIVE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.LISTEN).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theMainFlowStaysOnTheFirstScreenEvenWithAPreviewBelow() {
        render(state = previewedState())

        // 预览展开后页面变长，但首屏主流程仍不该被三张卡顶下去。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.SEND).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        state: SourceSyncImportState = SourceSyncImportState(),
        receiveState: SourceSyncReceiveState = SourceSyncReceiveState.Idle,
        listenState: SourceSyncListenState = SourceSyncListenState.Idle,
        listenTls: SourceSyncListenTlsAvailability = NO_TLS,
        sendState: SourceSyncSendState = SourceSyncSendState.Idle,
        onReceive: (SourceSyncReceiveForm) -> Unit = {},
        onReceiveCancel: () -> Unit = {},
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
                    receiveState = receiveState,
                    onReceive = onReceive,
                    onReceiveCancel = onReceiveCancel,
                    listenState = listenState,
                    listenTls = listenTls,
                    onListenStart = onListenStart,
                    onListenStop = {},
                    onListenReceive = {},
                    sendState = sendState,
                    onSend = onSend,
                )
            }
        }
    }

    private fun previewedState(): SourceSyncImportState {
        val plan = SourceSyncPlan(
            listOf(
                SourceSyncCandidate(
                    action = SourceSyncAction.CREATE,
                    reason = SourceSyncReasons.NEW_SOURCE,
                    syncSourceId = "sync-1",
                    sourceType = "emby",
                    isKnownSourceType = true,
                    displayName = "来源 sync-1",
                ),
            ),
        )
        return SourceSyncImportState(
            status = SourceSyncImportStatus.PREVIEWED,
            fileName = "device-a.json",
            envelopeJson = "{}",
            preview = SourceSyncImportLogic.previewView("device-a", "snap-1", plan),
        )
    }

    private companion object {
        val NO_TLS: SourceSyncListenTlsAvailability =
            SourceSyncListenTlsAvailability(false, SourceSyncListenCopy.TLS_UNAVAILABLE)
    }
}
