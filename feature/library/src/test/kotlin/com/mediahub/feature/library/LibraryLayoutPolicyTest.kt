package com.mediahub.feature.library

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLayoutPolicyTest {

    private fun item(
        id: String = "1",
        type: MediaType = MediaType.MOVIE,
        poster: String? = null,
        backdrop: String? = null,
    ) = MediaItem(
        serverId = "s",
        id = id,
        type = type,
        title = id,
        posterUrl = poster,
        backdropUrl = backdrop,
    )

    @Test
    fun `columns grow with width and respect view mode`() {
        assertEquals(1, libraryGridColumns(300f, LibraryViewMode.LARGE))
        assertEquals(2, libraryGridColumns(360f, LibraryViewMode.LARGE))
        assertEquals(3, libraryGridColumns(360f, LibraryViewMode.STANDARD))
        assertEquals(4, libraryGridColumns(480f, LibraryViewMode.STANDARD))
        assertEquals(1, libraryGridColumns(1200f, LibraryViewMode.LIST))
    }

    @Test
    fun `columns are clamped for very narrow and very wide screens`() {
        assertEquals(1, libraryGridColumns(80f, LibraryViewMode.LARGE))
        assertEquals(6, libraryGridColumns(2400f, LibraryViewMode.STANDARD))
    }

    @Test
    fun `episode and video always use landscape thumbnail`() {
        assertEquals(16f / 9f, artworkAspect(item(type = MediaType.EPISODE), false), 0.0001f)
        assertEquals(16f / 9f, artworkAspect(item(type = MediaType.VIDEO), true), 0.0001f)
    }

    @Test
    fun `portrait poster wins for movies`() {
        assertEquals(
            2f / 3f,
            artworkAspect(item(poster = "p.jpg", backdrop = "b.jpg"), landscapeWindow = true),
            0.0001f,
        )
    }

    @Test
    fun `backdrop only item falls back to landscape`() {
        assertEquals(16f / 9f, artworkAspect(item(backdrop = "b.jpg"), false), 0.0001f)
    }

    @Test
    fun `no artwork in landscape window is landscape`() {
        assertEquals(16f / 9f, artworkAspect(item(), landscapeWindow = true), 0.0001f)
        assertEquals(2f / 3f, artworkAspect(item(), landscapeWindow = false), 0.0001f)
    }

    @Test
    fun `title wrapping allows two lines in grid and three in list`() {
        assertEquals(2, titleMaxLines(LibraryViewMode.STANDARD))
        assertEquals(2, titleMaxLines(LibraryViewMode.LARGE))
        assertEquals(3, titleMaxLines(LibraryViewMode.LIST))
    }

    @Test
    fun `reorder moves item and is a no-op for invalid input`() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), reorderIds(ids, 0, 1))
        assertEquals(listOf("b", "c", "a"), reorderIds(ids, 0, 2))
        assertEquals(ids, reorderIds(ids, 1, 1))
        assertEquals(ids, reorderIds(ids, -1, 1))
        assertEquals(ids, reorderIds(ids, 0, 9))
    }
}
