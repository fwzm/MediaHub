package com.mediahub.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放请求头隔离（ADR-018）：每个 PlaybackEngine 拥有独立的
 * PlaybackHeadersHolder，不同播放会话/预加载/字幕请求互不污染。
 */
class PlaybackHeadersHolderTest {

    @Test
    fun `holders are isolated per engine`() {
        val engineA = PlaybackHeadersHolder()
        val engineB = PlaybackHeadersHolder()

        engineA.setHeaders(mapOf("X-Emby-Token" to "token-A"))
        assertTrue(engineB.headers.isEmpty())
        assertEquals("token-A", engineA.headers["X-Emby-Token"])

        engineB.setHeaders(mapOf("Cookie" to "quark=session-b"))
        // A 不受 B 影响
        assertEquals("token-A", engineA.headers["X-Emby-Token"])
        assertEquals("quark=session-b", engineB.headers["Cookie"])
    }

    @Test
    fun `set headers replaces previous session`() {
        val holder = PlaybackHeadersHolder()
        holder.setHeaders(mapOf("Authorization" to "Bearer old"))
        holder.setHeaders(mapOf("Authorization" to "Bearer new"))
        assertEquals("Bearer new", holder.headers["Authorization"])
    }
}
