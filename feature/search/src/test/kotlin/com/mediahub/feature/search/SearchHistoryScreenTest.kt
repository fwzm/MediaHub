package com.mediahub.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 搜索体验（条目 35/52/99/120）生产组件的交互契约：
 * 历史列表固定两行高、超出可滚动、长按删除单条、进入/再次选中搜索入口时聚焦输入框。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val history = listOf("冰血暴", "fargo", "真探", "切尔诺贝利", "老无所依")

    private fun render(
        history: List<String> = this.history,
        onSelectHistory: (String) -> Unit = {},
        onDeleteHistory: (String) -> Unit = {},
        token: () -> Int = { 0 },
    ) {
        composeRule.setContent {
            MaterialTheme {
                SearchContent(
                    query = "",
                    state = GlobalSearchState.idle(),
                    history = history,
                    onQueryChange = {},
                    onSelectHistory = onSelectHistory,
                    onDeleteHistory = onDeleteHistory,
                    focusRequestToken = token(),
                    onBack = {},
                    onOpenItem = { _: MediaItem -> },
                )
            }
        }
    }

    @Test
    fun `history list keeps a fixed two-row height`() {
        render()

        composeRule.onNodeWithTag(SearchTestTags.HISTORY_LIST)
            .assertHeightIsEqualTo(SearchHistoryLayout.LIST_HEIGHT)
    }

    @Test
    fun `history beyond two rows is reachable by scrolling`() {
        render()

        composeRule.onNodeWithTag(SearchTestTags.HISTORY_LIST)
            .performScrollToNode(hasText("老无所依"))

        composeRule.onNodeWithText("老无所依").assertIsDisplayed()
    }

    @Test
    fun `long press deletes only the pressed history entry`() {
        val deleted = mutableListOf<String>()
        render(onDeleteHistory = { deleted += it })

        composeRule.onNodeWithTag(SearchTestTags.historyItem("fargo"))
            .performTouchInput { longClick() }

        assertEquals(listOf("fargo"), deleted)
    }

    @Test
    fun `clicking a history entry reuses it as the query`() {
        val selected = mutableListOf<String>()
        render(onSelectHistory = { selected += it })

        composeRule.onNodeWithTag(SearchTestTags.historyItem("冰血暴")).performClick()

        assertEquals(listOf("冰血暴"), selected)
    }

    @Test
    fun `search input is focused on entering the page`() {
        render()

        composeRule.onNodeWithTag(SearchTestTags.INPUT).assertIsFocused()
    }

    @Test
    fun `selecting the search entry again re-focuses the input`() {
        composeRule.setContent {
            val token = remember { mutableStateOf(0) }
            val focusManager = LocalFocusManager.current
            MaterialTheme {
                Column {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            token.value += 1
                        },
                        modifier = androidx.compose.ui.Modifier.testTag("refocus"),
                    ) { Text("refocus") }
                    SearchContent(
                        query = "",
                        state = GlobalSearchState.idle(),
                        history = history,
                        onQueryChange = {},
                        onSelectHistory = {},
                        onDeleteHistory = {},
                        focusRequestToken = token.value,
                        onBack = {},
                        onOpenItem = { _: MediaItem -> },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("refocus").performClick()

        composeRule.onNodeWithTag(SearchTestTags.INPUT).assertIsFocused()
    }
}
