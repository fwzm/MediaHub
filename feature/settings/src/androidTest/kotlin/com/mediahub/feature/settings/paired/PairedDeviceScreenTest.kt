package com.mediahub.feature.settings.paired

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.common.pairing.PairedDevice
import com.mediahub.core.common.pairing.PairedDeviceListPresenter
import com.mediahub.core.common.pairing.PairedDeviceStoreReasons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-70：已配对设备页的界面证据（instrumentation，真跑在模拟器/真机上）。
 *
 * 与 JVM 的 [PairedDeviceControllerTest] 分工：那边钉状态机与存储语义，这边只钉「用户能看见并点到什么」——
 * 空态/豆腐块/容量提示都在、行上的「忘记 名字」能被点到、对话框的两个按钮各自回调、回执能被收掉。
 * 文案断言只用 [PairedDeviceCopy] 与 [PairedDeviceTags] 的常量，不写死布局位置。
 */
@RunWith(AndroidJUnit4::class)
class PairedDeviceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now: Long = 1_792_800_000_000L

    private fun device(deviceId: String, displayName: String, fingerprint: String): PairedDevice =
        PairedDevice.create(
            deviceId = deviceId,
            fingerprint = fingerprint,
            displayName = displayName,
            nowEpochMs = now,
            sourceCount = 2,
        )

    private fun list(vararg devices: PairedDevice, capacity: Int = 32) =
        PairedDeviceListPresenter.build(devices.toList(), capacity, now)

    private fun render(
        state: PairedDeviceUiState,
        onRefresh: () -> Unit = {},
        onAskForget: (String) -> Boolean = { true },
        onConfirmForget: () -> Unit = {},
        onCancelForget: () -> Unit = {},
        onDismissOutcome: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PairedDeviceScreen(
                    state = state,
                    onRefresh = onRefresh,
                    onAskForget = onAskForget,
                    onConfirmForget = onConfirmForget,
                    onCancelForget = onCancelForget,
                    onDismissOutcome = onDismissOutcome,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun anEmptyRegistryExplainsItselfInsteadOfShowingAnEmptyList() {
        render(PairedDeviceUiState(list = PairedDeviceListState.Ready(list())))

        composeRule.onNodeWithTag(PairedDeviceTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.HINT).assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.COUNT).assertTextContains("已配对 0 台")
        composeRule.onNodeWithTag(PairedDeviceTags.EMPTY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.EMPTY)
            .assertTextContains(PairedDeviceListPresenter.EMPTY_TEXT)
        // 豆腐块始终在：配对过 ≠ 可信。
        composeRule.onNodeWithTag(PairedDeviceTags.TOFU).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun eachRowShowsTheDeviceAndItsOwnForgetButton() {
        val forgotten = mutableListOf<String>()
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Ready(
                    list(device("device-a", "客厅 NAS", FINGERPRINT_A)),
                ),
            ),
            onAskForget = { deviceId ->
                forgotten += deviceId
                true
            },
        )

        composeRule.onNodeWithTag(PairedDeviceTags.ROW + ".0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("客厅 NAS").assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.FORGET + ".0").performScrollTo().performClick()

        assertEquals(listOf("device-a"), forgotten)
    }

    @Test
    fun aFullRegistryShowsTheCapacityWarning() {
        val devices = Array(2) { index -> device("device-" + index, "设备 " + index, FINGERPRINT_A) }
        render(PairedDeviceUiState(list = PairedDeviceListState.Ready(list(*devices, capacity = 2))))

        composeRule.onNodeWithTag(PairedDeviceTags.CAPACITY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.CAPACITY).assertTextContains("已达上限", substring = true)
    }

    @Test
    fun aBrokenDocumentIsExplainedWithoutHidingTheRestOfThePage() {
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Ready(
                    view = list(),
                    loadError = PairedDeviceStoreReasons.MALFORMED_JSON,
                ),
            ),
        )

        composeRule.onNodeWithTag(PairedDeviceTags.WARNING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.WARNING)
            .assertTextContains(PairedDeviceCopy.warningOf(PairedDeviceStoreReasons.MALFORMED_JSON))
        // 档案有问题也仍然能点「重新读取」，让用户自己再试一次。
        composeRule.onNodeWithTag(PairedDeviceTags.REFRESH).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aMissingStoragePortSaysSoInsteadOfPretendingTheListIsEmpty() {
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Unavailable(PairedDeviceController.STORAGE_UNAVAILABLE, null),
            ),
        )

        composeRule.onNodeWithTag(PairedDeviceTags.UNAVAILABLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.UNAVAILABLE)
            .assertTextContains(PairedDeviceController.STORAGE_UNAVAILABLE, substring = true)
        composeRule.onNodeWithText(PairedDeviceCopy.STORAGE_UNAVAILABLE_HINT).assertIsDisplayed()
        // 不可用的时候不能假装「0 台设备」。
        composeRule.onNodeWithTag(PairedDeviceTags.COUNT).assertDoesNotExist()
    }

    @Test
    fun theForgetDialogAsksFirstAndBothButtonsReportBack() {
        var confirmed = 0
        var cancelled = 0
        val device = device("device-a", "客厅 NAS", FINGERPRINT_A)
        val row = PairedDeviceListPresenter.buildRow(device)
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Ready(list(device)),
                prompt = PairedDeviceForgetPrompt(
                    deviceId = device.deviceId,
                    displayName = row.displayName,
                    fingerprintLabel = row.fingerprintLabel,
                    text = PairedDeviceCopy.forgetText(row.displayName, row.fingerprintLabel),
                ),
            ),
            onConfirmForget = { confirmed += 1 },
            onCancelForget = { cancelled += 1 },
        )

        composeRule.onNodeWithTag(PairedDeviceTags.PROMPT).assertIsDisplayed()
        composeRule.onNodeWithText(PairedDeviceCopy.FORGET_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.PROMPT_CANCEL).performClick()
        composeRule.onNodeWithTag(PairedDeviceTags.PROMPT_CONFIRM).performClick()

        assertEquals(1, cancelled)
        assertEquals(1, confirmed)
    }

    @Test
    fun aPendingForgetDisablesTheRowButtonsWhileItIsBeingWritten() {
        val device = device("device-a", "客厅 NAS", FINGERPRINT_A)
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Ready(list(device)),
                forgetting = true,
            ),
        )

        composeRule.onNodeWithTag(PairedDeviceTags.FORGET + ".0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(PairedDeviceTags.REFRESH).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theLastOutcomeIsShownAndCanBeDismissed() {
        var dismissed = 0
        render(
            state = PairedDeviceUiState(
                list = PairedDeviceListState.Ready(list()),
                outcome = PairedDeviceForgetOutcome(
                    forgotten = true,
                    title = PairedDeviceCopy.FORGET_DONE_TITLE,
                    message = PairedDeviceCopy.FORGET_DONE_MESSAGE,
                ),
            ),
            onDismissOutcome = { dismissed += 1 },
        )

        composeRule.onNodeWithTag(PairedDeviceTags.OUTCOME).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PairedDeviceTags.OUTCOME)
            .assertTextContains(PairedDeviceCopy.FORGET_DONE_TITLE, substring = true)
        composeRule.onNodeWithTag(PairedDeviceTags.OUTCOME_DISMISS).performScrollTo().performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun backClosesThePage() {
        var closed = 0
        render(
            state = PairedDeviceUiState(list = PairedDeviceListState.Ready(list())),
            onBack = { closed += 1 },
        )

        composeRule.onNodeWithContentDescription("返回").performClick()

        assertEquals(1, closed)
    }

    private companion object {
        const val FINGERPRINT_A: String =
            "3f2a9c1d4b6e8f0a2c4e6b8d0f1a3c5e7b9d1f3a5c7e9b0d2f4a6c8e0b1d3f5a"
    }
}
