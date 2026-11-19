package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 审计 P9（主控按钮组）的 ±10s 契约：生产 [PlayerTransportControls] 必须露出
 * 后退/播放/前进三个按钮，且每次点击只发出一次精确的 -10s / +10s 步进；
 * [playerSeekTarget] 负责把步进夹到 [0, duration] 内，不允许越界。
 *
 * 该测试直接组合生产 composable（不从 PlayerScreen 复制一份），
 * 因此 PlayerControls 回归到只有播放键时它必须 RED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerTransportControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun transportShowsBackPlayAndForwardButtons() {
        composeRule.setContent {
            MaterialTheme {
                PlayerTransportControls(
                    showPauseIcon = false,
                    onTogglePlayPause = {},
                    onSeekBy = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_seek_back_10))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_play))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_seek_forward_10))
            .assertIsDisplayed()
    }

    @Test
    fun seekBackSendsExactlyOneNegativeTenSecondStep() {
        val seen = mutableListOf<Long>()
        composeRule.setContent {
            MaterialTheme {
                PlayerTransportControls(
                    showPauseIcon = false,
                    onTogglePlayPause = {},
                    onSeekBy = { seen += it },
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_seek_back_10))
            .performClick()
        assertEquals(listOf(-PLAYER_SEEK_STEP_MS), seen)
    }

    @Test
    fun seekForwardSendsExactlyOnePositiveTenSecondStep() {
        val seen = mutableListOf<Long>()
        composeRule.setContent {
            MaterialTheme {
                PlayerTransportControls(
                    showPauseIcon = false,
                    onTogglePlayPause = {},
                    onSeekBy = { seen += it },
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_seek_forward_10))
            .performClick()
        assertEquals(listOf(PLAYER_SEEK_STEP_MS), seen)
        assertEquals(10_000L, PLAYER_SEEK_STEP_MS)
    }

    @Test
    fun pauseIconFollowsPlayWhenReadyAndToggles() {
        var toggles = 0
        composeRule.setContent {
            MaterialTheme {
                PlayerTransportControls(
                    showPauseIcon = true,
                    onTogglePlayPause = { toggles++ },
                    onSeekBy = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_pause))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun seekTargetClampsWithinMediaBounds() {
        assertEquals(0L, playerSeekTarget(positionMs = 3_000L, durationMs = 60_000L, deltaMs = -10_000L))
        assertEquals(60_000L, playerSeekTarget(positionMs = 58_000L, durationMs = 60_000L, deltaMs = 10_000L))
        assertEquals(17_000L, playerSeekTarget(positionMs = 7_000L, durationMs = 60_000L, deltaMs = 10_000L))
        assertEquals(0L, playerSeekTarget(positionMs = 3_000L, durationMs = 0L, deltaMs = -10_000L))
        assertEquals(13_000L, playerSeekTarget(positionMs = 3_000L, durationMs = 0L, deltaMs = 10_000L))
    }
}
