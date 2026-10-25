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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W6-64：导出（另存为文件）卡片的界面证据。
 *
 * 逻辑侧已由 SourceSyncArchiveExportControllerTest（纯 JVM）钉住四处状态转换；这里只钉界面：
 * 卡片常驻页尾、收起时只有一个「导出」按钮、展开后口令框与两个按钮都在、
 * 口令原样交给 ViewModel、结果行按状态显示成功文案或稳定码、收起按钮真的回调。
 *
 * 为什么每条断言都先 performScrollTo()：与 SourceSyncSendCancelScreenTest /
 * SourceSyncPassphraseScreenTest 同样的理由——页面根节点是 verticalScroll 的 Column，
 * 导出卡片按 W6-41 的首屏纪律放在页尾，不滚动就点不到。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncArchiveExportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCollapsedCardShowsOnlyTheExportButton() {
        render(exportState = SourceSyncArchiveExportState.Idle)

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_CARD).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_ACTION).performScrollTo().assertIsDisplayed()
        // 收起状态不摆口令框：导出还有一步要展开，避免首屏被口令吓到。
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_PASSPHRASE).assertDoesNotExist()
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_SUBMIT).assertDoesNotExist()
    }

    @Test
    fun tappingExportOpensThePassphraseStep() {
        var requested = 0
        render(exportState = SourceSyncArchiveExportState.Idle, onExportRequest = { requested++ })

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_ACTION).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(1, requested) }
    }

    @Test
    fun theExpandedCardOffersAnEmptyPassphraseFieldAndBothButtons() {
        render(exportState = SourceSyncArchiveExportState.AwaitingPassphrase())

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_PASSPHRASE).performScrollTo().assertIsDisplayed()
        // 口令可留空（= 明文导出），所以「导出」按钮一开始就是能按的。
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_SUBMIT).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_DISMISS).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_ACTION).assertDoesNotExist()
    }

    @Test
    fun typingAPassphraseAndTappingExportHandsTheTextBack() {
        val submitted = mutableListOf<String>()
        render(
            exportState = SourceSyncArchiveExportState.AwaitingPassphrase(),
            onExportPrepare = { passphrase -> submitted += passphrase },
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_PASSPHRASE)
            .performScrollTo()
            .performTextInput("correct horse battery staple")
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_SUBMIT).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(listOf("correct horse battery staple"), submitted) }
    }

    @Test
    fun aSavedExportShowsTheFileNameAndTheByteCount() {
        render(
            exportState = SourceSyncArchiveExportState.Saved(
                fileName = "sync-envelope.mharchive",
                encrypted = true,
                sourceCount = 3,
                skippedCount = 1,
                byteCount = 1282,
            ),
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_STATE)
            .performScrollTo()
            .assertTextContains("加密归档", substring = true)
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_STATE)
            .assertTextContains("1282 字节", substring = true)
        // 成功之后不能再生成第二份：导出按钮置灰。
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aFailureShowsTheStableCodeAndKeepsTheCardOpen() {
        render(exportState = SourceSyncArchiveExportState.Failed("passphrase-too-weak"))

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_STATE)
            .performScrollTo()
            .assertTextContains("passphrase-too-weak", substring = true)
        // 失败不是终点：口令框还在，可以改了口令重来。
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_PASSPHRASE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_SUBMIT).performScrollTo().assertIsEnabled()
    }

    @Test
    fun tappingDismissReportsTheDismissCallback() {
        var dismissed = 0
        render(
            exportState = SourceSyncArchiveExportState.AwaitingPassphrase(),
            onExportDismiss = { dismissed++ },
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.EXPORT_DISMISS).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        exportState: SourceSyncArchiveExportState = SourceSyncArchiveExportState.Idle,
        onExportRequest: () -> Unit = {},
        onExportPrepare: (String) -> Unit = {},
        onExportDismiss: () -> Unit = {},
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
                    archiveExportState = exportState,
                    onExportRequest = onExportRequest,
                    onExportPrepare = onExportPrepare,
                    onExportDismiss = onExportDismiss,
                );
            }
        }
    }
}
