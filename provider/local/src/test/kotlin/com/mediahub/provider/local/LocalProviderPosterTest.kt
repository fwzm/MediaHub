package com.mediahub.provider.local

import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalProviderPosterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun file_tree_exposes_a_poster_only_for_videos() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        val video = File(root, "sample.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val audio = File(root, "track.mp3").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val notes = File(root, "notes.txt").apply { writeText("not media") }
        File(root, "shows").mkdir()

        val page = provider(root).listFolder(null, PageRequest(limit = 50))
        val items = page.items.associateBy { it.title }

        val videoItem = items.getValue("sample")
        assertEquals(MediaType.VIDEO, videoItem.type)
        assertEquals(video.absoluteFile.toURI().toString(), videoItem.posterUrl)
        assertNull(items.getValue("track").posterUrl)
        assertNull(items.getValue("notes").posterUrl)
        assertNull(items.getValue("shows").posterUrl)
    }

    private fun provider(root: File) = LocalProvider(
        server = MediaServer(
            id = "local-test",
            name = "本地",
            type = ServerType.LOCAL,
            baseUrl = "",
            createdAtEpochMs = 0L,
        ),
        rootProvider = object : LocalRootProvider {
            override fun rootDirectories(): List<File> = listOf(root)
        },
    )
}
