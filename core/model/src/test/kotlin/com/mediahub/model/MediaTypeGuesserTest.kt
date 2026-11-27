package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeGuesserTest {

    @Test
    fun `video extensions map to VIDEO`() {
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("/Movies/Interstellar.mkv"))
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("content://tree/x/document/primary%3AMovies%2Ffilm.mp4"))
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("movie.MKV"))
    }

    @Test
    fun `audio extensions map to AUDIO`() {
        assertEquals(MediaType.AUDIO, MediaTypeGuesser.forPath("/Music/album/track.flac"))
        assertEquals(MediaType.AUDIO, MediaTypeGuesser.forPath("song.mp3"))
    }

    @Test
    fun `hls playlist extensions map to VIDEO`() {
        // 条目 136：带扩展名 / 无扩展名的 HLS 地址都应被当作可播放视频入口。
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("/live/channel.m3u8"))
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("https://media.example/list.m3u"))
        assertEquals(MediaType.VIDEO, MediaTypeGuesser.forPath("CHANNEL.M3U8"))
    }

    @Test
    fun `unknown falls back to OTHER`() {
        assertEquals(MediaType.OTHER, MediaTypeGuesser.forPath("/docs/readme.txt"))
        assertEquals(MediaType.OTHER, MediaTypeGuesser.forPath("no-extension"))
    }
}
