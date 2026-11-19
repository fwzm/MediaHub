package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #107: the player info row must render the shared server icon, not the raw icon URI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerServerIdentityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun builtinIconUsesInitialPlaceholder() {
        composeRule.setContent {
            MaterialTheme {
                PlayerServerIdentity(displayName = "Test Server", icon = "builtin://emby")
            }
        }
        composeRule.onNodeWithTag(PLAYER_SERVER_IDENTITY_ICON_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("T", useUnmergedTree = true).assertExists()
    }
}
