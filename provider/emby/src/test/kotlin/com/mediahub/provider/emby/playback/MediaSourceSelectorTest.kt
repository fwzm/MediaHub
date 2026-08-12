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
}
