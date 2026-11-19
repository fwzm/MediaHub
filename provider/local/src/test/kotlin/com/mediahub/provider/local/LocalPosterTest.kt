package com.mediahub.provider.local

import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPosterTest {

    @Test
    fun video_uris_become_poster_sources_without_copying_bytes() {
        val file = "file:/storage/emulated/0/Movies/sample.mp4"
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/video%3A1"

        assertEquals(file, LocalPoster.forItem(MediaType.VIDEO, file))
        assertEquals(saf, LocalPoster.forItem(MediaType.VIDEO, saf))
    }

    @Test
    fun non_video_types_never_get_a_poster_uri() {
        listOf(
            MediaType.FOLDER,
            MediaType.AUDIO,
            MediaType.SERIES,
            MediaType.SEASON,
            MediaType.EPISODE,
            MediaType.LIVE_TV,
            MediaType.OTHER,
        ).forEach { type ->
            assertNull("$type must not request a video frame", LocalPoster.forItem(type, "file:/tmp/a"))
        }
    }

    @Test
    fun blank_uri_is_never_used_as_a_poster() {
        assertNull(LocalPoster.forItem(MediaType.VIDEO, ""))
        assertNull(LocalPoster.forItem(MediaType.VIDEO, "   "))
    }
}
