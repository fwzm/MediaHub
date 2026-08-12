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
    fun `unknown falls back to OTHER`() {
        assertEquals(MediaType.OTHER, MediaTypeGuesser.forPath("/docs/readme.txt"))
        assertEquals(MediaType.OTHER, MediaTypeGuesser.forPath("no-extension"))
    }
}
