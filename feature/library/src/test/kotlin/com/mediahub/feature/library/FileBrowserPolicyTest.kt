package com.mediahub.feature.library

import com.mediahub.model.FileBrowserEntry
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.formatFileSize
import com.mediahub.model.toFileBrowserEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserPolicyTest {

    @Test
    fun `large mode is wider than standard mode`() {
        assertEquals(2, fileBrowserColumns(480f, LibraryViewMode.LARGE))
        assertEquals(3, fileBrowserColumns(480f, LibraryViewMode.STANDARD))
        assertEquals(1, fileBrowserColumns(900f, LibraryViewMode.LIST))
    }

    @Test
    fun `columns are clamped to 1-4`() {
        assertEquals(1, fileBrowserColumns(100f, LibraryViewMode.LARGE))
        assertEquals(4, fileBrowserColumns(2000f, LibraryViewMode.LARGE))
    }

    @Test
    fun `file browser titles wrap on both grid and list`() {
        assertEquals(2, fileBrowserTitleMaxLines(LibraryViewMode.LARGE))
        assertEquals(2, fileBrowserTitleMaxLines(LibraryViewMode.STANDARD))
        assertEquals(3, fileBrowserTitleMaxLines(LibraryViewMode.LIST))
    }

    @Test
    fun `media item projects into file browser entry`() {
        val item = MediaItem(
            serverId = "s",
            id = "f1",
            type = MediaType.FOLDER,
            title = "电影",
            path = "/media/movies",
            sizeBytes = 2048,
        )
        val entry: FileBrowserEntry = item.toFileBrowserEntry()
        assertEquals("电影", entry.name)
        assertEquals("/media/movies", entry.path)
        assertTrue(entry.isDirectory)
        assertEquals(2048L, entry.sizeBytes)
    }

    @Test
    fun `non folder item is not a directory`() {
        val item = MediaItem(serverId = "s", id = "m1", type = MediaType.MOVIE, title = "片")
        assertFalse(item.toFileBrowserEntry().isDirectory)
    }

    @Test
    fun `file size formatting handles unknown and byte scale`() {
        assertEquals("", formatFileSize(null))
        assertEquals("", formatFileSize(0))
        assertEquals("2 KB", formatFileSize(2048))
        assertEquals("1.5 MB", formatFileSize(1_572_864))
        assertEquals("2.0 GB", formatFileSize(2L * 1_073_741_824))
    }
}
