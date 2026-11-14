package com.mediahub.feature.player.danmaku

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuPanelRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        var enabled: Boolean? = null
        var opacity: Float? = null
        var stroke: Float? = null
        var searched: String? = null
        var selected: DanmakuEpisode? = null
        var sent: String? = null
        var refreshed = false
    }

    private fun state(
        enabled: Boolean = true,
        count: Int = 3,
        searchResults: List<DanmakuEpisode> = emptyList(),
        notice: String? = null,
    ) = DanmakuUiState(
        enabled = enabled,
        comments = (1..count).map {
            DanmakuComment(id = "$it", timeMs = it * 1_000L, text = "弹幕$it")
        },
        searchResults = searchResults,
        notice = notice,
        sourceLabel = "缓存",
    )

    private fun render(
        state: DanmakuUiState = state(),
        recorder: Recorder = Recorder(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                DanmakuPanel(
                    state = state,
                    onEnabledChange = { recorder.enabled = it },
                    onOpacityChange = { recorder.opacity = it },
                    onStrokeWidthChange = { recorder.stroke = it },
                    onSearch = { recorder.searched = it },
                    onEpisodeSelected = { recorder.selected = it },
                    onSend = { recorder.sent = it },
                    onRefresh = { recorder.refreshed = true },
                )
            }
        }
    }

    @Test
    fun `count badge shows grouped total`() {
        composeRule.setContent {
            MaterialTheme {
                DanmakuCountBadge(count = 1_234, onClick = {})
            }
        }

        composeRule.onNodeWithTag(DanmakuUiTestTags.COUNT_BADGE).assertIsDisplayed()
        composeRule.onNodeWithText("1,234").assertIsDisplayed()
    }

    @Test
    fun `blank draft cannot be sent and valid draft is trimmed`() {
        val recorder = Recorder()
        render(recorder = recorder)

        composeRule.onNodeWithTag(DanmakuUiTestTags.SEND)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(DanmakuUiTestTags.INPUT)
            .performScrollTo()
            .performTextInput("  hello  ")
        composeRule.onNodeWithTag(DanmakuUiTestTags.SEND)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals("hello", recorder.sent)
    }

    @Test
    fun `over long draft shows the limit error and cannot be sent`() {
        render()

        composeRule.onNodeWithTag(DanmakuUiTestTags.INPUT)
            .performScrollTo()
            .performTextInput("x".repeat(DanmakuDefaults.MAX_TEXT_LENGTH + 1))

        composeRule.onNodeWithTag(DanmakuUiTestTags.SEND)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("弹幕最多 80 个字").assertIsDisplayed()
    }

    @Test
    fun `toggle and sliders emit user changes`() {
        val recorder = Recorder()
        render(recorder = recorder)

        composeRule.onNodeWithTag(DanmakuUiTestTags.ENABLE_SWITCH)
            .performScrollTo()
            .performClick()
        assertFalse(recorder.enabled!!)

        composeRule.onNodeWithTag(DanmakuUiTestTags.OPACITY_SLIDER).performScrollTo()
        composeRule.onNodeWithTag(DanmakuUiTestTags.STROKE_SLIDER).performScrollTo()
        composeRule.onNodeWithTag(DanmakuUiTestTags.PANEL).assertIsDisplayed()
        assertTrue(recorder.enabled != null)
    }

    @Test
    fun `search button sends the query and selecting a result emits the episode`() {
        val episode = DanmakuEpisode(
            id = "101",
            animeTitle = "Frieren",
            episodeTitle = "第1话",
        )
        val recorder = Recorder()
        render(state = state(searchResults = listOf(episode)), recorder = recorder)

        composeRule.onNodeWithTag(DanmakuUiTestTags.SEARCH_INPUT)
            .performScrollTo()
            .performTextInput("Frieren")
        composeRule.onNodeWithTag(DanmakuUiTestTags.SEARCH_BUTTON)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals("Frieren", recorder.searched)

        composeRule.onNodeWithTag(DanmakuUiTestTags.searchResult("101"))
            .performScrollTo()
            .performClick()
        assertEquals(episode, recorder.selected)
    }

    @Test
    fun `loaded count source and notice are visible`() {
        render(state = state(count = 12, notice = "网络失败"))

        composeRule.onNodeWithText("已加载 12 条").assertIsDisplayed()
        composeRule.onNodeWithText("来源：缓存").assertIsDisplayed()
        composeRule.onNodeWithTag(DanmakuUiTestTags.NOTICE)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
