package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SEEK-A · **真实生产组件** `PlayerSeekBar` 的交互契约（先红后绿 + 双变异）。
 *
 * 为什么必须测生产组件：PR #53 在 `setContent` 里复制了一份 Slider，把生产
 * `onValueChange` 改成「变更即提交」后它们**仍然 4/0 全绿**（变异存活），
 * 因此那些测试不能认证真实播放器的 Seek 行为。本测试组合的是 `PlayerScreen.kt` 中
 * 生产 `PlayerControls` **真实引用**的同一个 composable。
 *
 * 为什么用真实触摸（performTouchInput.swipe）而不是语义动作 SetProgress：
 * 语义动作 `SetProgress` 在本环境会**连带触发** `onValueChangeFinished`
 * （设施行为），那样就永远测不出「拖动中不提交」。真实 swipe 才是用户的真实手势：
 * 拖动过程中多次 `onValueChange`，松手时一次 `onValueChangeFinished`。
 *
 * 契约：
 * 1. 拖动全程 **onSeek 只被调用 1 次**（松手那次），且值落在拖动终点附近；
 * 2. 拖动完成后 `onSeek` 不得被重复调用；
 * 3. durationMs <= 0 时禁用且不可提交。
 *
 * 变异验证（本测试的价值证明，见报告）：
 * - MUT-1：生产 `onValueChange` 改为同时提交 → 用例 1 必须 RED；
 * - MUT-2：移除 `onValueChangeFinished` 的提交 → 用例 1 必须 RED（0 次提交）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSeekBarContractTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        val seeks = mutableListOf<Long>()
    }

    private fun render(
        recorder: Recorder,
        durationMs: Long = 90_000L,
        positionMs: Long = 0L,
    ) {
        composeRule.setContent {
            MaterialTheme {
                // 组合的是**生产组件本身**（不是测试自建的 Slider 副本）。
                PlayerSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = { recorder.seeks += it },
                    colors = SliderDefaults.colors(),
                )
            }
        }
    }

    @Test
    fun `progress bar is discoverable by stable tag`() {
        render(Recorder())
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun `a real drag commits exactly once at release`() {
        val recorder = Recorder()
        render(recorder)
        val node = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)
        // 真实触摸：从 10% 拖到 50%（约 45s / 90s）
        node.performTouchInput {
            swipe(
                start = centerLeft + androidx.compose.ui.geometry.Offset(width * 0.10f, 0f),
                end = centerLeft + androidx.compose.ui.geometry.Offset(width * 0.50f, 0f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()

        assertEquals(
            "一次真实拖动必须只提交一次（拖动中是预览，不提交）；实际=${recorder.seeks}",
            1,
            recorder.seeks.size,
        )
        val committed = recorder.seeks.single()
        // 目标位置必须落在拖动终点附近（允许 Slider 步进/关键帧容差 10%）
        assertTrue(
            "提交位置应接近 50%%（45000ms），实际=${committed}ms",
            committed in 38_000L..52_000L,
        )
    }

    @Test
    fun `no further commits after the drag ends`() {
        val recorder = Recorder()
        render(recorder)
        val node = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)
        node.performTouchInput {
            swipe(
                start = centerLeft + androidx.compose.ui.geometry.Offset(width * 0.10f, 0f),
                end = centerLeft + androidx.compose.ui.geometry.Offset(width * 0.60f, 0f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        val afterDrag = recorder.seeks.size
        composeRule.waitForIdle()
        assertEquals("拖动结束后不得再有提交", afterDrag, recorder.seeks.size)
        assertEquals("并且恰好是一次", 1, recorder.seeks.size)
    }

    @Test
    fun `unknown duration disables the bar and never commits`() {
        val recorder = Recorder()
        render(recorder, durationMs = 0L)
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsNotEnabled()
        assertEquals("时长未知时不得伪造可 Seek", 0, recorder.seeks.size)
    }
}
