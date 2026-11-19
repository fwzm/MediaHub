package com.mediahub.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计 P11：音轨 / 字幕 / 播放信息 / 特效四个 Bottom Sheet 共用一个骨架，
 * 统一水平内边距 20dp、底部内边距 32dp，并允许各 sheet 自带纵向排列间距。
 *
 * 这里直接渲染共享内容容器（ModalBottomSheet 的宿主窗口在 Robolectric 下不可靠），
 * 验证真实生产组件而不是复制一份布局常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSheetScaffoldTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sharedContentAppliesHorizontalPadding() {
        composeRule.setContent {
            MaterialTheme {
                PlayerSheetContent(
                    modifier = Modifier.fillMaxWidth().testTag("sheetContent"),
                    scrollable = false,
                ) {
                    Spacer(Modifier.fillMaxWidth().height(40.dp).testTag("sheetChild"))
                }
            }
        }

        val content = composeRule.onNodeWithTag("sheetContent").getUnclippedBoundsInRoot()
        val child = composeRule.onNodeWithTag("sheetChild").getUnclippedBoundsInRoot()

        assertEquals(20f, child.left.value, 0.5f)
        assertEquals(20f, content.right.value - child.right.value, 0.5f)
    }

    @Test
    fun sharedContentAppliesBottomPadding() {
        composeRule.setContent {
            MaterialTheme {
                PlayerSheetContent(
                    modifier = Modifier.fillMaxWidth().testTag("sheetContent"),
                    scrollable = false,
                ) {
                    Spacer(Modifier.fillMaxWidth().height(40.dp).testTag("sheetChild"))
                }
            }
        }

        val content = composeRule.onNodeWithTag("sheetContent").getUnclippedBoundsInRoot()
        val child = composeRule.onNodeWithTag("sheetChild").getUnclippedBoundsInRoot()

        assertEquals(32f, content.bottom.value - child.bottom.value, 0.5f)
    }

    @Test
    fun sharedContentForwardsVerticalArrangement() {
        composeRule.setContent {
            MaterialTheme {
                PlayerSheetContent(
                    modifier = Modifier.fillMaxWidth().testTag("sheetContent"),
                    scrollable = false,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.fillMaxWidth().height(20.dp).testTag("firstChild"))
                    Spacer(Modifier.fillMaxWidth().height(20.dp).testTag("secondChild"))
                }
            }
        }

        val first = composeRule.onNodeWithTag("firstChild").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("secondChild").getUnclippedBoundsInRoot()

        assertEquals(12f, second.top.value - first.bottom.value, 0.5f)
    }
}
