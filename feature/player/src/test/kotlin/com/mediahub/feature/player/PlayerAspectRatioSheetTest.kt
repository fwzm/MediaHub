package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mediahub.model.PlayerAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerAspectRatioSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows every ratio and forwards the selected one`() {
        var selected: PlayerAspectRatio? = null
        composeRule.setContent {
            MaterialTheme {
                PlayerAspectRatioSheetContent(
                    selected = PlayerAspectRatio.FIT,
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("16:9").assertIsDisplayed()
        composeRule.onNodeWithText("4:3").performClick()

        assertEquals(PlayerAspectRatio.RATIO_4_3, selected)
    }
}
