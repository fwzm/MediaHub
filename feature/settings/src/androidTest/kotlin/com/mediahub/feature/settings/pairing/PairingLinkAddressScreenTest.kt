package com.mediahub.feature.settings.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.common.pairing.PairingAddressCandidates
import com.mediahub.core.common.pairing.PairingAddressInput
import com.mediahub.core.common.pairing.PairingAddressReasons
import com.mediahub.core.common.pairing.PairingInterfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-60：配对页「本机可被连的地址」区块的界面证据（instrumentation）。
 *
 * 只钉三件事：区块真的显示出来、三种状态下各给哪句话、**页面上没有任何「开始监听」入口**
 * （与 Windows 侧同义：候选地址只变成文字，从不变成 socket）。排序与拒绝原因的判定在
 * core 层的 JVM 测试里，这里不重测。
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkAddressScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun report(vararg inputs: PairingAddressInput) =
        PairingAddressCandidates.describe(inputs.toList())

    private val readyReport = report(
        PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
        PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5")),
    )

    private fun render(
        addressState: PairingAddressState = PairingAddressState.NotRead,
        onRefreshAddresses: () -> Unit = {},
        state: PairingLinkState = PairingLinkState.Idle,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PairingLinkScreen(
                    state = state,
                    onVerify = {},
                    onFetch = {},
                    onClear = {},
                    onBack = {},
                    addressState = addressState,
                    onRefreshAddresses = onRefreshAddresses,
                )
            }
        }
    }

    @Test
    fun theAddressBlockIsShownWithItsDisplayOnlyHint() {
        render()

        composeRule.onNodeWithTag(PairingLinkTags.ADDRESSES).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(PairingAddressSummary.TITLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(PairingAddressSummary.DISPLAY_ONLY_HINT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(PairingAddressSummary.NOT_READ_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnavailableStateSaysThereIsNoReaderInsteadOfPretendingItIsEmpty() {
        render(addressState = PairingAddressState.Unavailable)

        composeRule.onNodeWithText(PairingAddressSummary.UNAVAILABLE_HINT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aReadyStateShowsTheHeadlineAndOneLinePerCandidate() {
        render(addressState = PairingAddressState.Ready(readyReport))

        composeRule.onNodeWithText(PairingAddressSummary.lineOf(readyReport)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairingLinkTags.ADDRESS_LINE + ".0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairingLinkTags.ADDRESS_LINE + ".1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("192.168.1.20（Ethernet，有线）").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptyReportShowsTheNoUsableAddressCode() {
        render(addressState = PairingAddressState.Ready(PairingAddressCandidates.describe(emptyList())))

        composeRule.onNodeWithTag(PairingLinkTags.ADDRESS_STATE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            PairingAddressSummary.lineOf(PairingAddressCandidates.describe(emptyList())),
        ).performScrollTo().assertIsDisplayed()
        assertTrue(PairingAddressSummary.lineOf(PairingAddressCandidates.describe(emptyList()))
            .contains(PairingAddressReasons.NO_USABLE_ADDRESS))
    }

    @Test
    fun aFailedReportShowsTheEnumerationFailedCode() {
        render(addressState = PairingAddressState.Ready(PairingAddressCandidates.failed()))

        composeRule.onNodeWithText(PairingAddressSummary.lineOf(PairingAddressCandidates.failed()))
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(PairingAddressSummary.lineOf(PairingAddressCandidates.failed())
            .contains(PairingAddressReasons.ENUMERATION_FAILED))
    }

    @Test
    fun theRefreshButtonIsEnabledAndTheClickIsReported() {
        var refreshes = 0
        render(onRefreshAddresses = { refreshes += 1 })

        composeRule.onNodeWithTag(PairingLinkTags.ADDRESS_REFRESH).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun thePageOffersNoListenButtonAtAll() {
        render(addressState = PairingAddressState.Ready(readyReport))

        // 这一页只展示地址、不发包：任何「开始监听」之类的按钮都不该存在。
        composeRule.onNodeWithText("开始监听").assertDoesNotExist()
        composeRule.onNodeWithText("监听").assertDoesNotExist()
    }
}
