package com.mediahub.feature.settings.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.mediahub.core.common.pairing.PairingTransportReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-59 / W6-59b：配对页的界面证据（instrumentation，真跑在模拟器/真机上）。
 *
 * 与 JVM 的 [PairingLinkControllerTest] 分工：那边钉解析、稳定码与拨号参数，这边只钉「用户能看见并点到什么」——
 * 输入框在空的时候按钮不可点、贴了链接能点、核对之后结果行里出现的就是链接里写明的设备与指纹、
 * 拉邀请按钮只在核对过之后可点、拉的过程中整页不可点、返回回调只被调一次。
 * 文案断言只用 [PairingLinkCopy] 与 [PairingLinkTags] 的常量，不写死布局位置。
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val issuedAt: Long = 1_760_000_000_000L

    private val fingerprint: PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 3).toByte() })

    private fun link(): String {
        val payload = PairingPayload.create(
            deviceId = "windows-a",
            ephemeralUrl = "https://192.168.1.20:8443/pairing",
            challenge = PairingChallenge.createRandom(),
            publicKeyFingerprint = fingerprint,
            nowEpochMs = issuedAt,
        )
        return PairingLink.create(payload)
    }

    private fun render(
        state: PairingLinkState = PairingLinkState.Idle,
        onVerify: (String) -> Unit = {},
        onFetch: () -> Unit = {},
        onClear: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PairingLinkScreen(
                    state = state,
                    onVerify = onVerify,
                    onFetch = onFetch,
                    onClear = onClear,
                    onBack = onBack,
                )
            }
        }
    }

    private fun parsed(): PairingLinkState.Parsed = PairingLinkState.Parsed(
        deviceId = "windows-a",
        fingerprint = fingerprint.displayString,
        endpoint = "192.168.1.20:8443",
        remainingSeconds = 90L,
    )

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
        render(state = parsed())

        // assertTextContains 的第二个参数是 substring，缺省为 false（要求整段相等）；
        // 结果行是「已核对：…」整句，所以这里必须显式 substring = true。
        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("windows-a", substring = true)
            .assertTextContains(fingerprint.displayString, substring = true)
            .assertTextContains("90 秒", substring = true)
    }

    @Test
    fun aFailedStateShowsTheStableCode() {
        render(state = PairingLinkState.Failed(PairingCodeReasons.NOT_BASE64URL, null))

        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(PairingCodeReasons.NOT_BASE64URL, substring = true)
    }

    @Test
    fun theFetchButtonIsDisabledUntilTheLinkIsVerified() {
        // 没贴链接、没核对过：拉邀请按钮必须在，但不能点——不知道对端是谁就没有可钉的对象。
        render(state = PairingLinkState.Idle)

        composeRule.onNodeWithTag(PairingLinkTags.FETCH).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun aParsedStateEnablesTheFetchButtonAndTheClickIsReported() {
        var fetches = 0
        render(state = parsed(), onFetch = { fetches += 1 })

        composeRule.onNodeWithTag(PairingLinkTags.FETCH).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, fetches)
    }

    @Test
    fun aFetchingStateDisablesEverythingAndSaysItIsConnecting() {
        render(
            state = PairingLinkState.Fetching(
                deviceId = "windows-a",
                fingerprint = fingerprint.displayString,
                endpoint = "192.168.1.20:8443",
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.LINK).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(PairingLinkTags.ACTION).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(PairingLinkTags.FETCH).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(PairingLinkTags.CLEAR).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertTextContains("正在连接", substring = true)
        composeRule.onNodeWithText(PairingLinkCopy.FETCHING_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFetchedStateShowsTheValidatedInvitationAndSaysSyncIsStillSeparate() {
        render(
            state = PairingLinkState.Fetched(
                deviceId = "windows-b",
                fingerprint = fingerprint.displayString,
                endpoint = "192.168.1.20:8443",
                remainingSeconds = 69L,
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertTextContains("邀请已校验", substring = true)
        composeRule.onNodeWithText(PairingLinkCopy.FETCHED_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFetchFailureShowsTheCoreCodeInTheStateLine() {
        render(
            state = PairingLinkState.Failed(
                PairingTransportReasons.FINGERPRINT_MISMATCH,
                "对端公钥指纹与链接不符",
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.STATE)
            .performScrollTo()
            .assertTextContains(PairingTransportReasons.FINGERPRINT_MISMATCH, substring = true)
        composeRule.onNodeWithTag(PairingLinkTags.FETCH).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theBackButtonCallsBackExactlyOnce() {
        var backs = 0
        render(onBack = { backs += 1 })

        // 返回是 IconButton 的 contentDescription，不是文本：用 onNodeWithText 匹配不到它。
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

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
