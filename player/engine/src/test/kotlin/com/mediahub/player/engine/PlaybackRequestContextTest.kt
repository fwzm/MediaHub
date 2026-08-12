package com.mediahub.player.engine

import com.mediahub.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackRequestContextTest {
    @Test
    fun `two playback sessions cannot overwrite each other's authorization`() {
        val emby = PlaybackRequestContext.from(
            PlaybackSource(url = "https://emby.invalid/video", headers = mapOf("X-Emby-Token" to "emby-token"))
        )
        val quark = PlaybackRequestContext.from(
            PlaybackSource(url = "https://quark.invalid/video", cookies = mapOf("session" to "quark-cookie"))
        )

        assertEquals("emby-token", emby.headers["X-Emby-Token"])
        assertFalse(emby.headers.containsKey("Cookie"))
        assertEquals("session=quark-cookie", quark.headers["Cookie"])
        assertFalse(quark.headers.containsKey("X-Emby-Token"))
    }

    @Test
    fun `request context defensively copies mutable input`() {
        val headers = mutableMapOf("Authorization" to "first")
        val context = PlaybackRequestContext.of(headers)
        headers["Authorization"] = "second"

        assertEquals("first", context.headers["Authorization"])
    }
}
