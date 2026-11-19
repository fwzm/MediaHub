package com.mediahub.feature.detail.source

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceOptionsTest {

    @Test
    fun `maps occurrences in resolver order with user facing labels`() {
        val resolution = SourceResolution.Completed(
            occurrences = listOf(
                occurrence("server-a", "a1", "服务器 A", active = true),
                occurrence("server-a", "a2", "服务器 A"),
                occurrence("server-b", "b1", "服务器 B"),
            ),
            truncated = false,
        )

        val options = playbackSourceOptions(resolution)

        assertEquals(listOf("服务器 A · 副本1", "服务器 A · 副本2", "服务器 B"), options.map { it.label })
        assertEquals(listOf("a1", "a2", "b1"), options.map { it.itemId })
        assertEquals(listOf("server-a", "server-a", "server-b"), options.map { it.serverId })
    }

    @Test
    fun `carries playback snapshot fields from each occurrence item`() {
        val resolution = SourceResolution.Completed(
            occurrences = listOf(occurrence("server-a", "a1", "服务器 A", active = true)),
            truncated = true,
        )

        val option = playbackSourceOptions(resolution).single()

        assertEquals(MediaType.MOVIE, option.type)
        assertEquals(7_200_000L, option.runtimeMs)
        assertEquals("poster-a1", option.posterUrl)
        assertEquals("mkv", option.container)
    }

    private fun occurrence(serverId: String, itemId: String, serverName: String, active: Boolean = false) =
        CanonicalSourceOccurrence(
            serverId = serverId,
            serverName = serverName,
            item = MediaItem(
                serverId = serverId,
                id = itemId,
                type = MediaType.MOVIE,
                title = "电影 $itemId",
                posterUrl = "poster-$itemId",
                runtimeMs = 7_200_000L,
                container = "mkv",
            ),
            isActive = active,
        )
}

