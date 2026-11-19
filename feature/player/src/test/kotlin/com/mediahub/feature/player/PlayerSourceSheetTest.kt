package com.mediahub.feature.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mediahub.model.PlaybackSourceOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSourceSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows every source and forwards the selected one`() {
        var selected: PlaybackSourceOption? = null
        val choices = listOf(
            PlayerSourceChoice(option("server-a", "a1", "线路 A"), "线路 A", true),
            PlayerSourceChoice(option("server-b", "b1", "线路 B"), "线路 B", false),
        )
        compose.setContent {
            PlayerSourceSheetContent(choices = choices, onSelect = { selected = it })
        }

        compose.onNodeWithText("线路 A").assertExists()
        compose.onNodeWithText("线路 B").performClick()

        assertEquals("server-b", selected?.serverId)
    }

    private fun option(serverId: String, itemId: String, label: String) = PlaybackSourceOption(
        serverId = serverId,
        serverName = label,
        itemId = itemId,
        title = label,
        label = label,
    )
}

