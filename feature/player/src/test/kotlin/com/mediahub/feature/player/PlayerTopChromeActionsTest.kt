package com.mediahub.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计 P4：播放页顶部 chrome 的四个入口必须是**可发现的图标按钮**，而不是一行纯文字按钮。
 *
 * 契约（对每个入口独立断言，负向对照才能恰好 1 红）：
 * 1. 每个入口都能用稳定的本地化 contentDescription 找到并显示；
 * 2. 点击每个入口只触发它自己的回调一次（不得串线到别的入口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerTopChromeActionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class Recorder {
        var visualEffects = 0
        var info = 0
        var audio = 0
        var subtitle = 0
    }

    private fun render(recorder: Recorder) {
        composeRule.setContent {
            MaterialTheme {
                PlayerTopChromeActions(
                    onShowVisualEffects = { recorder.visualEffects++ },
                    onShowInfo = { recorder.info++ },
                    onShowAudio = { recorder.audio++ },
                    onShowSubtitle = { recorder.subtitle++ },
                )
            }
        }
    }

    private fun description(id: Int): String = composeRule.activity.getString(id)

    @Test
    fun visualEffectsEntryIsAnIconButtonWithLocalizedDescription() {
        val recorder = Recorder()
        render(recorder)
        composeRule
            .onNodeWithContentDescription(description(R.string.player_visual_effects_entry_description))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, recorder.visualEffects)
        assertEquals(0, recorder.info + recorder.audio + recorder.subtitle)
    }

    @Test
    fun infoEntryIsAnIconButtonWithLocalizedDescription() {
        val recorder = Recorder()
        render(recorder)
        composeRule
            .onNodeWithContentDescription(description(R.string.player_info_entry_description))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, recorder.info)
        assertEquals(0, recorder.visualEffects + recorder.audio + recorder.subtitle)
    }

    @Test
    fun audioEntryIsAnIconButtonWithLocalizedDescription() {
        val recorder = Recorder()
        render(recorder)
        composeRule
            .onNodeWithContentDescription(description(R.string.player_audio_tracks_description))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, recorder.audio)
        assertEquals(0, recorder.visualEffects + recorder.info + recorder.subtitle)
    }

    @Test
    fun subtitleEntryIsAnIconButtonWithLocalizedDescription() {
        val recorder = Recorder()
        render(recorder)
        composeRule
            .onNodeWithContentDescription(description(R.string.player_subtitles_description))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, recorder.subtitle)
        assertEquals(0, recorder.visualEffects + recorder.info + recorder.audio)
    }
}
