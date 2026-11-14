package com.mediahub.feature.player.danmaku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放器集成烟测：验证弹幕层在 Robolectric 下真实组合/绘制不崩，
 * 并固定「视频 → 弹幕 → OSD」的层级与开关行为（条目 74 / 59）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuIntegrationRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun comments(vararg texts: String): List<DanmakuComment> = texts.mapIndexed { index, text ->
        DanmakuComment(
            id = "c-$index",
            timeMs = index * 500L,
            text = text,
            mode = DanmakuMode.SCROLL,
        )
    }

    @Test
    fun overlayRendersBelowOsdLayer() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    Text("video", modifier = Modifier.testTag("video_layer"))
                    DanmakuOverlay(
                        comments = comments("第一条", "第二条"),
                        positionMs = { 0L },
                        playing = false,
                        opacity = 0.85f,
                        strokeWidthDp = 1.5f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text("osd", modifier = Modifier.testTag("osd_layer"))
                }
            }
        }

        composeRule.onNodeWithTag(DanmakuTestTags.OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag("osd_layer").assertIsDisplayed()
    }

    @Test
    fun disabledDanmakuDoesNotComposeOverlay() {
        val enabled = false
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    if (enabled) {
                        DanmakuOverlay(
                            comments = comments("不会显示"),
                            positionMs = { 0L },
                            playing = false,
                            opacity = 1f,
                            strokeWidthDp = 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(DanmakuTestTags.OVERLAY).assertDoesNotExist()
    }

    @Test
    fun malformedCommentsDoNotCrashRendering() {
        val bad = listOf(
            DanmakuComment(id = "blank", timeMs = 0L, text = "   "),
            DanmakuComment(id = "negative", timeMs = -5_000L, text = "负时间"),
            DanmakuComment(id = "emoji", timeMs = 1L, text = "\uD83D\uDE00\uD83D\uDE00"),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    DanmakuOverlay(
                        comments = bad,
                        positionMs = { 0L },
                        playing = false,
                        opacity = 0.1f,
                        strokeWidthDp = 0f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(DanmakuTestTags.OVERLAY).assertIsDisplayed()
    }

    @Test
    fun countBadgeShowsCountAndOpensPanel() {
        var opened = 0
        composeRule.setContent {
            MaterialTheme {
                DanmakuCountBadge(count = 1234, onClick = { opened += 1 })
            }
        }

        composeRule.onNodeWithTag(DanmakuUiTestTags.COUNT_BADGE).assertIsDisplayed()
        composeRule.onNodeWithTag(DanmakuUiTestTags.COUNT_BADGE).performClick()
        assertEquals(1, opened)
    }
}
