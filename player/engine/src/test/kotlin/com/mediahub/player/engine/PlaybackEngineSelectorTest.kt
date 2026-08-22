package com.mediahub.player.engine

import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** PlaybackEngineSelector 纯逻辑测试（U3-A）。 */
class PlaybackEngineSelectorTest {

    private fun source(
        container: String? = "mkv",
        video: String? = "hevc",
        audio: String? = "aac",
    ) = PlaybackSource(
        url = "http://media/stream",
        container = container,
        videoCodec = video,
        audioCodec = audio,
        mode = PlaybackMode.DIRECT_PLAY,
    )

    @Test
    fun `explicit media3 mode wins`() {
        val selection = PlaybackEngineSelector.select(source(), PlaybackEngineMode.MEDIA3, emptySet())
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `explicit mpv mode wins`() {
        val selection = PlaybackEngineSelector.select(source(), PlaybackEngineMode.MPV, emptySet())
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `auto defaults to media3 fast path`() {
        val selection = PlaybackEngineSelector.select(source(), PlaybackEngineMode.AUTO, emptySet())
        assertEquals(EngineKind.MEDIA3, selection.kind)
    }

    @Test
    fun `auto routes known failure signature to mpv`() {
        val key = CompatibilitySignature.from(source(container = "mpegts", video = "h264", audio = "aac")).key
        val selection = PlaybackEngineSelector.select(
            source(container = "mpegts", video = "h264", audio = "aac"),
            PlaybackEngineMode.AUTO,
            setOf(key),
        )
        assertEquals(EngineKind.MPV, selection.kind)
    }

    @Test
    fun `signature key is case-normalized`() {
        val upper = CompatibilitySignature.from(source(container = "MKV", video = "H264", audio = "AAC"))
        val lower = CompatibilitySignature.from(source(container = "mkv", video = "h264", audio = "aac"))
        assertEquals(upper.key, lower.key)
    }

    @Test
    fun `auto prefers mpv for dts family audio`() {
        for (codec in listOf("dts", "DTS-HD MA", "dts-hd", "truehd")) {
            val selection = PlaybackEngineSelector.select(source(audio = codec), PlaybackEngineMode.AUTO, emptySet())
            assertEquals("codec=$codec", EngineKind.MPV, selection.kind)
        }
    }

    @Test
    fun `auto keeps media3 for common codecs`() {
        for (codec in listOf("aac", "eac3", "flac", "ac3", "opus", null)) {
            val selection = PlaybackEngineSelector.select(source(audio = codec), PlaybackEngineMode.AUTO, emptySet())
            assertEquals("codec=$codec", EngineKind.MEDIA3, selection.kind)
        }
    }
}
