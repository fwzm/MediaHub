package com.mediahub.app.navigation

import android.net.Uri
import com.mediahub.core.common.NavArgCodec
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackLaunchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerRouteBuilderTest {

    @Test
    fun `encodes the snapshot and switch position`() {
        val route = PlayerRouteBuilder.build(
            serverId = "server-a",
            itemId = "item-1",
            snapshot = PlaybackLaunchSnapshot(
                itemId = "item-1",
                type = MediaType.MOVIE,
                title = "视频/第二集",
                runtimeMs = 7_200_000L,
                posterUrl = "http://poster/1.jpg",
                container = "mkv",
            ),
            startPositionMs = 42_000L,
        )

        val parsed = Uri.parse(route)
        assertEquals("视频/第二集", parsed.getQueryParameter("title"))
        assertEquals("MOVIE", parsed.getQueryParameter("type"))
        assertEquals("42000", parsed.getQueryParameter("start"))
        assertTrue(route.startsWith("player/server-a/${NavArgCodec.encode("item-1")}?"))
    }

    @Test
    fun `omits start when no explicit position exists`() {
        val route = PlayerRouteBuilder.build(
            serverId = "server-a",
            itemId = "item-1",
            snapshot = PlaybackLaunchSnapshot(itemId = "item-1", title = "电影"),
        )

        assertFalse(route.contains("start="))
    }
}

