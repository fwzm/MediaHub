package com.mediahub.feature.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.feature.search.engine.UnifiedSearchHit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchBodyLoadMoreTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadMoreButtonAppearsWhenAnotherPageExistsAndForwardsClicks() {
        var clicks = 0
        val state = GlobalSearchState(
            query = "矩阵",
            hits = listOf(
                UnifiedSearchHit(
                    item = MediaItem(
                        serverId = "server-a",
                        id = "item-1",
                        type = MediaType.VIDEO,
                        title = "矩阵",
                    ),
                    serverName = "服务器 A",
                ),
            ),
            completedServers = setOf("server-a"),
            nextOffsets = mapOf("server-a" to 30),
        )

        composeRule.setContent {
            MaterialTheme {
                SearchBody(
                    state = state,
                    onOpenItem = {},
                    onLoadMore = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithText("加载更多").assertExists().performClick()
        assertEquals(1, clicks)
    }
}
