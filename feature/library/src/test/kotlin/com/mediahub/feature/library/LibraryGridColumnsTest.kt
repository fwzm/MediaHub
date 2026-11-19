package com.mediahub.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 审计 #97：文件浏览网格必须真正使用自适应列数，而不只是策略函数返回正确值。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "sw600dp-land")
class LibraryGridColumnsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tablet width places the fourth item in the first row`() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.requiredWidth(600.dp)) {
                    LibraryContent(
                        state = LibraryUiState.Content(
                            items = (1..6).map { index ->
                                MediaItem(
                                    serverId = "server",
                                    id = "item-$index",
                                    type = MediaType.VIDEO,
                                    title = "条目 $index",
                                )
                            },
                            libraryName = "测试库",
                        ),
                        fallbackName = "测试库",
                        onBack = {},
                        onOpenLibrary = {},
                        onOpenItem = {},
                        onOpenFavorites = null,
                        onRefresh = {},
                        onLoadMore = {},
                        onGoToParent = {},
                        onOpenFolder = {},
                        onSortSelected = {},
                        onFilterSelected = {},
                    )
                }
            }
        }

        val first = composeRule.onNodeWithText("条目 1").getBoundsInRoot()
        val fourth = composeRule.onNodeWithText("条目 4").getBoundsInRoot()
        assertEquals(
            "平板宽度下第 4 个条目应与第 1 个同处第一行",
            first.top.value,
            fourth.top.value,
            1f,
        )
    }
}
