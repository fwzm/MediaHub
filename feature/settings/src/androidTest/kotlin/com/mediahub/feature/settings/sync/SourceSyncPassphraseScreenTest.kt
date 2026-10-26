package com.mediahub.feature.settings.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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
 * W6-63：加密归档（.mharchive）口令卡的界面证据。
 *
 * 逻辑侧已由 SourceSyncArchiveControllerTest（纯 JVM）钉住嗅探与解开后的回调；这里只钉四条：
 * 没有提示时不出现任何口令控件、有时口令框与解密按钮都在、空口令按不动、输了字按下去
 * 真把口令原样交给 ViewModel，且错误行只在 error 非空时出现。
 *
 * 为什么每条断言都先 performScrollTo()：与 SourceSyncSendCancelScreenTest 同样的理由——
 * 页面根节点是 verticalScroll 的 Column，真机视口里页尾的卡片看得见但点不到。口令卡
 * 位于首屏（说明句与选择文件按钮之后），仍按同一纪律写，避免以后移动位置时静默失效。
 */
@RunWith(AndroidJUnit4::class)
class SourceSyncPassphraseScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingAnotherArchiveWithTheSameNameClearsThePassphrase() {
        val prompt = mutableStateOf(SourceSyncArchivePrompt(visible = true, fileName = "same.mharchive", selectionId = 1))
        composeRule.setContent {
            MaterialTheme {
                SourceSyncImportScreen(
                    state = SourceSyncImportState(), onPick = {}, onPreview = {},
                    onDecide = { _, _ -> }, onApply = {}, onBack = {}, archivePrompt = prompt.value,
                )
            }
        }
        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE).performScrollTo().performTextInput("fixture passphrase")
        composeRule.runOnIdle { prompt.value = prompt.value.copy(selectionId = 2) }
        assertEquals("", composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE)
            .performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun noPassphraseCardIsRenderedWhenThePickerDidNotSeeAnArchive() {
        render(archivePrompt = SourceSyncArchivePrompt())

        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_CARD).assertDoesNotExist()
        composeRule.onNodeWithTag(SourceSyncImportTags.UNLOCK).assertDoesNotExist()
        // 明文路径的首屏不受影响。
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun anArchiveShowsTheCardWithItsFileNameAndADisabledUnlockButton() {
        render(archivePrompt = SourceSyncArchivePrompt(visible = true, fileName = "backup.mharchive"))

        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_CARD).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE).performScrollTo().assertIsDisplayed()
        // 还没输入口令：按钮常驻但按不动，避免用空口令去解（会白跑一次 PBKDF2）。
        composeRule.onNodeWithTag(SourceSyncImportTags.UNLOCK).performScrollTo().assertIsNotEnabled()
        // 错误行只在 error 非空时出现。
        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_ERROR).assertDoesNotExist()
    }

    @Test
    fun typingAPassphraseEnablesUnlockAndTappingItHandsTheTextBack() {
        val submitted = mutableListOf<String>()
        render(
            archivePrompt = SourceSyncArchivePrompt(visible = true, fileName = "backup.mharchive"),
            onUnlockArchive = { passphrase -> submitted += passphrase },
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE)
            .performScrollTo()
            .performTextInput("correct horse battery staple")
        val unlock = composeRule.onNodeWithTag(SourceSyncImportTags.UNLOCK).performScrollTo()
        unlock.assertIsEnabled()
        unlock.performClick()

        // 口令原样交给 ViewModel；卡片自己不留副本、不回显。
        composeRule.runOnIdle { assertEquals(listOf("correct horse battery staple"), submitted) }
    }

    @Test
    fun aRejectedPassphraseKeepsTheCardOpenAndShowsTheReasonCode() {
        // 错误行用的是控制器给的固定措辞（解密失败 + ArchiveError 原码），界面只负责显示。
        render(
            archivePrompt = SourceSyncArchivePrompt(
                visible = true,
                fileName = "backup.mharchive",
                error = SourceSyncArchiveCopy.errorOf("wrong-passphrase-or-tampered"),
            ),
        )

        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_CARD).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_ERROR)
            .performScrollTo()
            .assertTextContains("wrong-passphrase-or-tampered", substring = true)
    }

    @Test
    fun thePassphraseCardSitsAboveThePreviewBlockAndKeepsTheMainFlowOnTheFirstScreen() {
        render(archivePrompt = SourceSyncArchivePrompt(visible = true, fileName = "backup.mharchive"))

        // 不滚动：口令卡与首屏的选择文件 / 预览按钮一起可见。
        composeRule.onNodeWithTag(SourceSyncImportTags.PASSPHRASE_CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PICK).assertIsDisplayed()
        composeRule.onNodeWithTag(SourceSyncImportTags.PREVIEW).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 夹具

    private fun render(
        archivePrompt: SourceSyncArchivePrompt = SourceSyncArchivePrompt(),
        onUnlockArchive: (String) -> Unit = {},
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
                    archivePrompt = archivePrompt,
                    onUnlockArchive = onUnlockArchive,
                )
            }
        }
    }
}
