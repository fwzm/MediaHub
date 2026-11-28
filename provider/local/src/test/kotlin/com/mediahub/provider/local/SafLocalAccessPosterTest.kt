package com.mediahub.provider.local

import android.net.Uri
import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafLocalAccessPosterTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
    private val rootId = "primary:Movies"
    private val videoId = "video:1"
    private val audioId = "audio:1"
    private val folderId = "folder:1"

    @Test
    fun saf_tree_exposes_a_poster_only_for_videos() = runBlocking {
        val page = LocalProvider(
            server = MediaServer(
                id = "saf-test",
                name = "SAF",
                type = ServerType.LOCAL,
                baseUrl = tree,
                createdAtEpochMs = 0L,
            ),
            rootProvider = FakeRoots(),
        ).listFolder(null, PageRequest(limit = 50))

        val items = page.items.associateBy { it.title }
        val video = items.getValue("movie")
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals(SafUri.documentUri(tree, videoId), video.posterUrl)
        assertEquals(SafUri.documentUri(tree, audioId), items.getValue("song").path)
        assertNull(items.getValue("song").posterUrl)
        assertNull(items.getValue("Trailers").posterUrl)
    }

    private inner class FakeRoots : LocalRootProvider, SafTreeNavigator.SafSource {
        override fun rootDirectories(): List<File> = emptyList()
        override fun contentRoots(): List<Uri> = listOf(Uri.parse(tree))
        private val rootRow = SafTreeNavigator.SafRow(rootId, "Movies", DIRECTORY, null)
        private val rows = listOf(
            SafTreeNavigator.SafRow(videoId, "movie.mp4", "video/mp4", 10),
            SafTreeNavigator.SafRow(audioId, "song.mp3", "audio/mpeg", 10),
            SafTreeNavigator.SafRow(folderId, "Trailers", DIRECTORY, null),
        )

        override fun metadata(documentUri: String): SafTreeNavigator.SafRow? = when (documentUri) {
            SafUri.documentUri(tree, rootId) -> rootRow
            SafUri.documentUri(tree, videoId) -> rows[0]
            SafUri.documentUri(tree, audioId) -> rows[1]
            SafUri.documentUri(tree, folderId) -> rows[2]
            else -> null
        }

        override fun listChildren(childrenUri: String): List<SafTreeNavigator.SafRow> = rows

        override fun canOpen(documentUri: String): Boolean = true
    }

    private companion object {
        const val DIRECTORY = "vnd.android.document/directory"
    }
}
