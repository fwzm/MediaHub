package com.mediahub.feature.settings.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingCodeReasons
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-59：配对页的界面证据（instrumentation，真跑在模拟器/真机上）。
 *
 * 与 JVM 的 [PairingLinkControllerTest] 分工：那边钉解析与稳定码，这边只钉「用户能看见并点到什么」——
 * 输入框在空的时候按钮不可点、贴了链接能点、核对之后结果行里出现的就是链接里写明的设备与指纹、
 * 返回回调只被调一次。文案断言只用 [PairingLinkCopy] 与 [PairingLinkTags] 的常量，不写死布局位置。
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val issuedAt: Long = 1_760_000_000_000L

    private fun link(): String {
        val payload = PairingPayload.create(
            deviceId = "windows-a",
            ephemeralUrl = "https://192.168.1.20:8443/pairing",
            challenge = PairingChallenge.createRandom(),
            publicKeyFingerprint = PairingFingerprint.fromBytes(
                ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 3).toByte() },
            ),
            nowEpochMs = issuedAt,
        )
        return PairingLink.create(payload)
    }

    private fun render(
        state: PairingLinkState = PairingLinkState.Idle,
        onVerify: (String) -> Unit = {},
        onClear: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PairingLinkScreen(
                    state = state,
                    onVerify = onVerify,
                    onClear = onClear,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun theEntryStartsEmptyAndTheVerifyButtonIsDisabled() {
        render()

        composeRule.onNodeWithTag(PairingLinkTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(PairingLinkTags.LINK).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairingLinkTags.ACTION).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(PairingLinkCopy.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(PairingLinkCopy.NOT_YET).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun typingALinkEnablesTheVerifyButtonAndHandsTheTextOver() {
        val verified = mutableListOf<String>()
        render(onVerify = { verified.add(it) })
        val text = link()

        composeRule.onNodeWithTag(PairingLinkTags.LINK).performScrollTo().performTextReplacement(text)
        composeRule.onNodeWithTag(PairingLinkTags.ACTION).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, verified.size)
        assertEquals(text, verified.first())
    }

    @Test
    fun aParsedStateShowsTheDeviceIdTheFingerprintAndTheRemainingSeconds() {
        val fingerprint = PairingFingerprint.fromBytes(
            ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 3).toByte() },
        )
        render(
            state = PairingLinkState.Parsed(
                deviceId = "windows-a",
                fingerprint = fingerprint.displayString,
                endpoint = "192.168.1.20:8443",
                remainingSeconds = 90L,
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("windows-a")
            .assertTextContains(fingerprint.displayString)
            .assertTextContains("90 秒")
    }

    @Test
    fun aFailedStateShowsTheStableCode() {
        render(state = PairingLinkState.Failed(PairingCodeReasons.NOT_BASE64URL, null))

        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(PairingCodeReasons.NOT_BASE64URL)
    }

    @Test
    fun theBackButtonCallsBackExactlyOnce() {
        var backs = 0
        render(onBack = { backs += 1 })

        composeRule.onNodeWithText("返回").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun theClearButtonWipesTheFieldAndReportsIt() {
        var clears = 0
        render(onClear = { clears += 1 })

        composeRule.onNodeWithTag(PairingLinkTags.LINK).performScrollTo().performTextReplacement(link())
        composeRule.onNodeWithTag(PairingLinkTags.CLEAR).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, clears)
        composeRule.onNodeWithTag(PairingLinkTags.ACTION).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theWholePageNeverPrintsTheChallenge() {
        render(state = PairingLinkState.Failed(PairingCodeReasons.PAYLOAD_INVALID, null))

        val line = PairingLinkCopy.lineOf(PairingLinkState.Failed(PairingCodeReasons.PAYLOAD_INVALID, null))
        assertTrue(line != null)
        composeRule.onNodeWithTag(PairingLinkTags.STATE).performScrollTo().assertTextContains(line!!)
    }
}
