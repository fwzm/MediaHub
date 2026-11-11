package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production seek control: held-pointer preview, release and actual recomposition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSeekContractRobolectricTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        val commits = mutableListOf<Long>()
    }

    private fun render(
        recorder: Recorder,
        position: MutableState<Long> = mutableStateOf(0L),
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerSeekBar(
                    positionMs = position.value,
                    durationMs = 90_000L,
                    onSeek = { recorder.commits += it },
                    colors = SliderDefaults.colors(),
                )
            }
        }
    }

    @Test
    fun progressSliderIsDiscoverableByStableTag() {
        render(Recorder())
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun draggingUpdatesPreviewOnly() {
        val recorder = Recorder()
        render(recorder)
        val node = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)
        // Keep the pointer down between injections so the assertion precedes release.
        node.performTouchInput {
            down(Offset(width * 0.10f, center.y))
            advanceEventTime(200)
            moveTo(center)
        }
        composeRule.waitForIdle()
        val preview = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        try {
            assertTrue("the production slider must display the held drag preview", preview in 38_000f..52_000f)
            composeRule.runOnIdle {
                assertTrue("a held pointer must not commit a seek", recorder.commits.isEmpty())
            }
        } finally {
            node.performTouchInput { up() }
        }
        composeRule.runOnIdle {
            assertEquals("release commits the actual final preview exactly once", listOf(preview.roundToLong()), recorder.commits)
        }
    }

    @Test
    fun releaseCommitsLastPreviewValue() {
        val recorder = Recorder()
        render(recorder)
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).performTouchInput {
            swipe(
                start = Offset(width * 0.10f, center.y),
                end = center,
                durationMillis = 300,
            )
        }
        composeRule.runOnIdle {
            assertEquals("one real gesture produces one commit", 1, recorder.commits.size)
            assertTrue("the target is near the gesture endpoint", recorder.commits.single() in 38_000L..52_000L)
        }
    }

    @Test
    fun progressTagIsStableAcrossRecomposition() {
        val recorder = Recorder()
        val position = mutableStateOf(0L)
        render(recorder, position)
        val node = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)
        node.assertIsDisplayed()
        composeRule.runOnIdle { position.value = 30_000L }
        composeRule.waitForIdle()
        node.assertIsDisplayed()
        assertEquals(30_000f, node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        composeRule.runOnIdle {
            assertTrue("a playback position update must not seek", recorder.commits.isEmpty())
        }
    }
}
