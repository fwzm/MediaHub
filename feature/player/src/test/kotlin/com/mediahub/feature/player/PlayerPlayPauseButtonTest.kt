package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerPlayPauseButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "player.play_pause"

    @Test
    fun `visible play button renders and forwards the click`() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                PlayerPlayPauseButton(
                    visible = true,
                    showPauseIcon = false,
                    onTogglePlayPause = { clicks += 1 },
                )
            }
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `hidden play button is absent from the overlay`() {
        composeRule.setContent {
            MaterialTheme {
                PlayerPlayPauseButton(
                    visible = false,
                    showPauseIcon = false,
                    onTogglePlayPause = {},
                )
            }
        }
        composeRule.onNodeWithTag(tag).assertDoesNotExist()
    }
}
