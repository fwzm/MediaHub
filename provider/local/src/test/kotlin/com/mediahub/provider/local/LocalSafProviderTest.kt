package com.mediahub.provider.local

import android.net.Uri
import com.mediahub.model.*
import com.mediahub.provider.api.ProviderException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSafProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val tree = "content://fixture.documents/tree/root%3Amedia"
    private val directory = SafUri.documentUri(tree, "opaque-folder")
    private val video = SafUri.documentUri(tree, "opaque-video")
    private val subtitle = SafUri.documentUri(tree, "opaque-srt")

    private inner class Roots(val files: List<File> = emptyList()) : LocalRootProvider, SafTreeNavigator.SafSource {
        var granted = true
        var rejectQuery = false
        val calls = mutableListOf<String>()
        val rows = mapOf(
            "root:media" to listOf(
                SafTreeNavigator.SafRow("opaque-video", "电影 a+b%.mkv", "video/x-matroska", 512L),
                SafTreeNavigator.SafRow("opaque-folder", "Child directory", "vnd.android.document/directory", null),
                SafTreeNavigator.SafRow("opaque-srt", "电影 a+b%.zh.srt", "application/x-subrip", 20L),
                SafTreeNavigator.SafRow("opaque-audio", "sound.flac", "audio/flac", 30L),
            ),
            "opaque-folder" to listOf(SafTreeNavigator.SafRow("nested-file", "nested.mp4", "video/mp4", 42L)),
        )
        override fun rootDirectories() = files
        override fun contentRoots() = if (granted) listOf(Uri.parse(tree)) else emptyList()
        override fun listChildren(childrenUri: String): List<SafTreeNavigator.SafRow> {
            calls += childrenUri
            if (rejectQuery) throw SecurityException("fixture grant revoked")
            return rows[SafUri.docId(childrenUri.substringBefore("/children"))].orEmpty()
        }
        override fun canOpen(documentUri: String): Boolean {
            calls += "open:$documentUri"
            return granted && documentUri in setOf(video, subtitle, SafUri.documentUri(tree, "nested-file"))
        }
        // Used by the document-access implementation once the reserved source interface is extended.
        override fun metadata(documentUri: String): SafTreeNavigator.SafRow? {
            calls += "stat:$documentUri"
            if (!granted) throw SecurityException("fixture grant revoked")
            val id = SafUri.docId(documentUri)
            return if (id == "root:media") SafTreeNavigator.SafRow(id, "Media", "vnd.android.document/directory", null)
            else rows.values.flatten().firstOrNull { it.docId == id }
        }
    }

    private fun provider(roots: Roots, root: String = tree) = LocalProvider(
        MediaServer(id = "fixture-local", name = "Fixture", type = ServerType.LOCAL, baseUrl = root, createdAtEpochMs = 0), roots,
    )
    private fun videoItem(uri: String = video, source: String = "fixture-local") = MediaItem(
        source, uri, MediaType.VIDEO, "电影 a+b%", path = uri,
        parentId = SafUri.documentUri(tree, "root:media"),
    )

    @Test fun configuredTreeListsItsChildrenInsteadOfLegacyFiles() = runBlocking {
        val legacy = temp.newFolder(); File(legacy, "legacy.mp4").writeText("fixture")
        val roots = Roots(listOf(legacy)); val result = provider(roots).listFolder(null, PageRequest(limit = 20))
        assertEquals(4, result.items.size)
        assertEquals(directory, result.items.first().id)
        assertTrue(result.items.all { it.id.startsWith(tree + "/document/") })
        assertFalse(result.items.any { it.title == "legacy" })
    }

    @Test fun opaqueNestedDocumentIdsRemainTreeBacked() = runBlocking {
        val roots = Roots(); val child = MediaItem("fixture-local", directory, MediaType.FOLDER, "Child", path = directory)
        val result = provider(roots).listFolder(child, PageRequest())
        assertEquals(listOf(SafUri.documentUri(tree, "nested-file")), result.items.map { it.id })
        assertTrue(roots.calls.any { it == SafUri.childrenUri(tree, "opaque-folder") })
    }

    @Test fun treePagingUsesCumulativeOffsetsWithoutRepeats() = runBlocking {
        val p = provider(Roots())
        val a = p.listFolder(null, PageRequest(limit = 2))
        assertTrue(a.hasMore); assertEquals(2, a.nextOffset)
        val b = p.listFolder(null, PageRequest(offset = a.nextOffset!!, limit = 2))
        assertEquals(4, (a.items + b.items).map { it.id }.distinct().size)
        assertFalse(b.hasMore); assertNull(b.nextOffset)
    }

    @Test fun detailReadsDocumentMetadataAndPreservesUnicodeName() = runBlocking {
        val result = runCatching { provider(Roots()).getItemDetail(video) }
        assertTrue("Configured document detail must be readable", result.isSuccess)
        val item = result.getOrThrow().item
        assertEquals(video, item.id); assertEquals("电影 a+b%", item.title)
        assertEquals(512L, item.sizeBytes); assertEquals(MediaType.VIDEO, item.type)
    }

    @Test fun playbackKeepsContentUriAndRequiresAnOpenableDocument() = runBlocking {
        val roots = Roots(); val result = runCatching { provider(roots).resolvePlayback(videoItem(), PlaybackOptions()) }
        assertTrue("Configured content URI must resolve for playback", result.isSuccess)
        assertEquals(video, result.getOrThrow().url)
        assertEquals("video/x-matroska", result.getOrThrow().mimeType)
        assertTrue(roots.calls.contains("open:$video"))
    }

    @Test fun siblingSubtitleDiscoveryPreservesTheTreeUri() = runBlocking {
        val found = provider(Roots()).discoverSubtitles(videoItem())
        assertEquals(listOf(subtitle), found.map { it.uri })
        assertEquals("zh", found.single().language)
        assertEquals("电影 a+b%.zh.srt", found.single().fileName)
    }

    @Test fun revokedTreeDoesNotFallBackToAnUnrelatedLegacyRoot() = runBlocking {
        val legacy = temp.newFolder(); File(legacy, "legacy.mp4").writeText("fixture")
        val roots = Roots(listOf(legacy)).also { it.granted = false }
        assertFalse(provider(roots).testConnection().ok)
        assertTrue(runCatching { provider(roots).listFolder(null, PageRequest()) }.isFailure)
        assertTrue(roots.calls.isEmpty())
    }

    @Test fun aForeignTreeIsRejectedBeforeAnyDocumentIo() = runBlocking {
        val roots = Roots(); val foreign = "content://other.documents/tree/other/document/file"
        val result = runCatching { provider(roots).listFolder(videoItem(foreign), PageRequest()) }
        assertTrue("Foreign document must not be treated as an empty folder", result.isFailure)
        assertTrue(roots.calls.isEmpty())
    }

    @Test fun anotherSourceItemIsRejectedBeforeAnyDocumentIo() = runBlocking {
        val roots = Roots()
        val result = runCatching { provider(roots).resolvePlayback(videoItem(source = "another-source"), PlaybackOptions()) }
        assertTrue(result.isFailure)
        assertTrue(roots.calls.isEmpty())
    }

    @Test fun aDeniedQueryIsNotReportedAsAnEmptyDirectory() = runBlocking {
        val roots = Roots().also { it.rejectQuery = true }
        assertTrue(runCatching { provider(roots).listFolder(null, PageRequest()) }.isFailure)
    }

    @Test fun aLegacyPrivateFileWithSpacesStillResolves() = runBlocking {
        val root = temp.newFolder(); val file = File(root, "legacy a+b%.mp4").apply { writeText("fixture") }
        val result = runCatching {
            provider(Roots(listOf(root)), root = "").resolvePlayback(
                MediaItem("fixture-local", file.absolutePath, MediaType.VIDEO, "Legacy", path = file.absolutePath), PlaybackOptions())
        }
        assertTrue("Legacy file paths must retain correctly escaped file URIs", result.isSuccess)
        assertEquals(file.toURI().toString(), result.getOrThrow().url)
    }
}
