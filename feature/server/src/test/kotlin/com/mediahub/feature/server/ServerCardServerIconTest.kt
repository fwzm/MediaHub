package com.mediahub.feature.server

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #107: the home server card must show the shared placeholder icon for builtin icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerCardServerIconTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun builtinIconUsesInitialPlaceholder() {
        val server = MediaServer(
            id = "srv-1",
            name = "Test Server",
            type = ServerType.EMBY,
            baseUrl = "http://127.0.0.1:8096",
            createdAtEpochMs = 0L,
        ).copy(icon = "builtin://emby")
        composeRule.setContent {
            MaterialTheme {
                ServerCard(server = server, onClick = {})
            }
        }
        composeRule.onNodeWithTag(SERVER_CARD_ICON_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("T", useUnmergedTree = true).assertExists()
    }
}
