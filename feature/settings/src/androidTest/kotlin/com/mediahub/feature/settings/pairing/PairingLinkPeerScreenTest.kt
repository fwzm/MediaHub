package com.mediahub.feature.settings.pairing

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
import com.mediahub.core.common.pairing.PairedDeviceReasons
import com.mediahub.core.common.pairing.PairedFingerprintState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-71：配对页「对端身份」区块的界面证据（instrumentation）。
 *
 * 与 JVM 的 [PairingLinkPeerTest] 分工：那边钉比对与登记语义，这边只钉「用户能看见什么、什么时候能点」——
 * 没核对过 / 拿不到卡片 / 没有登记簿 / 指纹不一致，按钮都不可点；只有「已比对且可登记」时才点亮。
 * 断言只用 [PairingLinkTags] 与 [PairingPeerCopy] 的常量，不写死布局位置。
 *
 * 注意：一个测试方法里只能 setContent 一次（Compose 测试规则的限制，CI 上会报
 * `Cannot call setContent twice per test!`），所以每个状态各用一个测试方法。
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkPeerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fingerprint: String = "7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"

    private fun render(
        peerState: PairingPeerState,
        linkState: PairingLinkState = PairingLinkState.Parsed(
            deviceId = "windows-a",
            fingerprint = fingerprint,
            endpoint = "192.168.1.20:8443",
            remainingSeconds = 90L,
        ),
        onRegisterPeer: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PairingLinkScreen(
                    state = linkState,
                    peerState = peerState,
                    onVerify = {},
                    onFetch = {},
                    onClear = {},
                    onBack = {},
                    onRegisterPeer = onRegisterPeer,
                )
            }
        }
    }

    private fun reviewed(
        state: PairedFingerprintState = PairedFingerprintState.NEW,
        reason: String = PairedDeviceReasons.FINGERPRINT_NEW,
        canRegister: Boolean = true,
    ): PairingPeerState.Reviewed = PairingPeerState.Reviewed(
        deviceId = "windows-a",
        displayName = "客厅 Windows",
        fingerprint = fingerprint,
        state = state,
        reason = reason,
        pinnedFingerprint = if (state == PairedFingerprintState.NEW) null else fingerprint,
        canRegister = canRegister,
    )

    @Test
    fun thePeerBlockIsAlwaysThereAndStartsUnchecked() {
        render(peerState = PairingPeerState.NotChecked)

        composeRule.onNodeWithTag(PairingLinkTags.PEER).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairingLinkTags.PEER_HINT).performScrollTo().assertIsDisplayed()
        // 还没核对过：没有结果行，按钮也不可点。
        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE).assertDoesNotExist()
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aNewlyReviewedCardShowsTheDeviceAndEnablesRegistration() {
        var registers = 0
        render(peerState = reviewed(), onRegisterPeer = { registers += 1 })

        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("windows-a", substring = true)
            .assertTextContains("客厅 Windows", substring = true)
            .assertTextContains("新设备", substring = true)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, registers)
    }

    @Test
    fun aMismatchedCardKeepsTheButtonDisabledAndSaysItWillNotOverwrite() {
        render(
            peerState = reviewed(
                state = PairedFingerprintState.MISMATCH,
                reason = PairedDeviceReasons.FINGERPRINT_MISMATCH,
                canRegister = false,
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertTextContains("指纹不一致", substring = true)
            .assertTextContains(PairingPeerCopy.MISMATCH_NOTE, substring = true)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRecordedOutcomeShowsTheReceiptAndDisablesTheButton() {
        render(
            peerState = PairingPeerState.Recorded(
                deviceId = "windows-a",
                displayName = "客厅 Windows",
                fingerprint = fingerprint,
                state = PairedFingerprintState.NEW,
                reason = PairedDeviceReasons.REGISTERED,
                accepted = true,
                added = true,
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertTextContains("已登记", substring = true)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRejectedRegistrationSaysWhyAndStaysDisabled() {
        render(
            peerState = PairingPeerState.Recorded(
                deviceId = "windows-a",
                displayName = "客厅 Windows",
                fingerprint = fingerprint,
                state = PairedFingerprintState.MISMATCH,
                reason = PairedDeviceReasons.FINGERPRINT_MISMATCH,
                accepted = false,
                added = false,
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertTextContains("没有登记", substring = true)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aMissingCardExplainsItself() {
        render(peerState = PairingPeerState.NoCard)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertTextContains(PairingPeerCopy.NO_CARD_HINT)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aMissingRegistryExplainsItself() {
        render(peerState = PairingPeerState.Unavailable)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_STATE)
            .performScrollTo()
            .assertTextContains(PairingPeerCopy.UNAVAILABLE_HINT)
        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aFetchingPageDisablesRegistrationEvenWhenReviewed() {
        render(
            peerState = reviewed(),
            linkState = PairingLinkState.Fetching(
                deviceId = "windows-a",
                fingerprint = fingerprint,
                endpoint = "192.168.1.20:8443",
            ),
        )

        composeRule.onNodeWithTag(PairingLinkTags.PEER_REGISTER).performScrollTo().assertIsNotEnabled()
    }
}
