package com.mediahub.core.common

import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackSourceOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceHandoffTest {

    @After
    fun tearDown() {
        PlayerSourceHandoff.clearFor("server-a", "item-1")
        PlayerSourceHandoff.clearFor("server-a", "item-2")
        PlayerSourceHandoff.clearFor("server-b", "item-2")
    }

    @Test
    fun `publishes only the matching item and keeps source order`() {
        val options = listOf(option("server-a", "item-1", "线路 A"), option("server-b", "item-1", "线路 B"))
        PlayerSourceHandoff.publish("server-a", "item-1", options, truncated = false)

        val snapshot = PlayerSourceHandoff.snapshotFor("server-a", "item-1")

        assertEquals(listOf("线路 A", "线路 B"), snapshot?.options?.map { it.label })
        assertEquals(false, snapshot?.truncated)
    }

    @Test
    fun `stale item does not leak into another player`() {
        PlayerSourceHandoff.publish("server-a", "item-1", listOf(option("server-a", "item-1", "线路 A")))

        assertNull(PlayerSourceHandoff.snapshotFor("server-a", "item-2"))
    }

    @Test
    fun `caps options and marks truncated`() {
        val options = (1..70).map { option("server-a", "item-1", "线路 $it") }
        PlayerSourceHandoff.publish("server-a", "item-1", options)

        val snapshot = PlayerSourceHandoff.snapshotFor("server-a", "item-1")

        assertEquals(64, snapshot?.options?.size)
        assertTrue(snapshot?.truncated == true)
    }

    @Test
    fun `clearing one item leaves another item untouched`() {
        PlayerSourceHandoff.publish("server-a", "item-1", listOf(option("server-a", "item-1", "线路 A")))
        PlayerSourceHandoff.publish("server-a", "item-2", listOf(option("server-a", "item-2", "线路 B")))

        PlayerSourceHandoff.clearFor("server-a", "item-1")

        assertNull(PlayerSourceHandoff.snapshotFor("server-a", "item-1"))
        assertEquals("线路 B", PlayerSourceHandoff.snapshotFor("server-a", "item-2")?.options?.single()?.label)
    }

    private fun option(serverId: String, itemId: String, label: String) = PlaybackSourceOption(
        serverId = serverId,
        serverName = label,
        itemId = itemId,
        title = label,
        label = label,
        type = MediaType.MOVIE,
    )
}

