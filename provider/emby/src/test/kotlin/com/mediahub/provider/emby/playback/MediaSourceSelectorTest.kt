package com.mediahub.provider.emby.playback

import com.mediahub.provider.emby.api.EmbyMediaSourceInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MediaSource 选择纯函数（Phase 1B-2 无转码规则）。 */
class MediaSourceSelectorTest {
    @Test
    fun `returns null when no source supports direct stream`() {
        val sources = listOf(
            EmbyMediaSourceInfoDto(id = "a", supportsDirectStream = false, supportsTranscoding = true),
            EmbyMediaSourceInfoDto(id = "b", supportsDirectPlay = true, supportsDirectStream = false),
        )
        assertNull(MediaSourceSelector.selectDirectStream(sources))
        assertNull(MediaSourceSelector.selectDirectStream(emptyList()))
    }

    @Test
    fun `returns first direct stream capable source in server order`() {
        val sources = listOf(
            EmbyMediaSourceInfoDto(id = "dovi", supportsDirectStream = false),
            EmbyMediaSourceInfoDto(id = "src2", supportsDirectStream = true),
            EmbyMediaSourceInfoDto(id = "src3", supportsDirectStream = true),
        )
        assertEquals("src2", MediaSourceSelector.selectDirectStream(sources)!!.id)
    }

    @Test
    fun `skips blu-ray iso source even when it supports direct stream`() {
        val sources = listOf(
            EmbyMediaSourceInfoDto(
                id = "iso",
                container = "mpegts",
                supportsDirectStream = true,
                path = "http://cdn/movie/zootopia.iso",
            ),
            EmbyMediaSourceInfoDto(
                id = "mkv",
                container = "mkv",
                supportsDirectStream = true,
                path = "http://cdn/movie/zootopia.remux.mkv",
            ),
        )
        assertEquals("mkv", MediaSourceSelector.selectDirectStream(sources)!!.id)
    }

    @Test
    fun `iso detection ignores query string`() {
        val sources = listOf(
            EmbyMediaSourceInfoDto(
                id = "iso",
                supportsDirectStream = true,
                path = "http://cdn/movie/foo.ISO?token=1",
            ),
        )
        assertNull(MediaSourceSelector.selectDirectStream(sources))
    }
}
