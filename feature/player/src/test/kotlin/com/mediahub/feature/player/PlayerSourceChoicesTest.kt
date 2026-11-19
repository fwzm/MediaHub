package com.mediahub.feature.player

import com.mediahub.model.PlaybackSourceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceChoicesTest {

    @Test
    fun `marks the current source and offers all lines`() {
        val options = listOf(option("server-a", "a1", "线路 A"), option("server-a", "a2", "线路 A2"), option("server-b", "b1", "线路 B"))

        val choices = playerSourceChoices(options, currentServerId = "server-a", currentItemId = "a1")

        assertEquals(3, choices.size)
        assertEquals(listOf(true, false, false), choices.map { it.isCurrent })
        assertTrue(shouldShowPlayerSourceSelector(options))
    }

    @Test
    fun `single server with copies does not offer a line picker`() {
        val options = listOf(option("server-a", "a1", "线路 A"), option("server-a", "a2", "线路 A2"))

        assertFalse(shouldShowPlayerSourceSelector(options))
    }

    private fun option(serverId: String, itemId: String, label: String) = PlaybackSourceOption(
        serverId = serverId,
        serverName = label,
        itemId = itemId,
        title = label,
        label = label,
    )
}

