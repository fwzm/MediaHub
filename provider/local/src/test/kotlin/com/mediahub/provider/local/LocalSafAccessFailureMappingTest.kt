package com.mediahub.provider.local

import android.net.Uri
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 部分设备数据访问失败（用户条目 149 / 审计 62）：
 * 个别设备的 DocumentsProvider 会对 metadata / listChildren / canOpen 抛
 * SecurityException / IllegalStateException / IllegalArgumentException / FileNotFoundException。
 * 这些必须统一映射为 DirectoryAccessRequired（可重授权文案），绝不能冒泡成崩溃或原始异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSafAccessFailureMappingTest {

    private val tree = "content://fixture.documents/tree/root%3Amedia"
    private val rootDoc = SafUri.documentUri(tree, "root:media")
    private val video = SafUri.documentUri(tree, "opaque-video")

    private class Boom(val error: Exception) : RuntimeException(error)

    private inner class Roots : LocalRootProvider, SafTreeNavigator.SafSource {
        var metadataFailure: (() -> Exception)? = null
        var listFailure: (() -> Exception)? = null
        var canOpenFailure: (() -> Exception)? = null

        private fun sink(factory: (() -> Exception)?) {
            factory?.let { throw it() }
        }

        override fun rootDirectories(): List<File> = emptyList()
        override fun contentRoots(): List<Uri> = listOf(Uri.parse(tree))

        override fun metadata(documentUri: String): SafTreeNavigator.SafRow? {
            sink(metadataFailure)
            val id = SafUri.docId(documentUri)
            return if (id == "root:media") {
                SafTreeNavigator.SafRow(id, "Media", "vnd.android.document/directory", null)
            } else {
                SafTreeNavigator.SafRow(id, "video.mkv", "video/x-matroska", 512L)
            }
        }

        override fun listChildren(childrenUri: String): List<SafTreeNavigator.SafRow> {
            sink(listFailure)
            return listOf(SafTreeNavigator.SafRow("opaque-video", "video.mkv", "video/x-matroska", 512L))
        }

        override fun canOpen(documentUri: String): Boolean {
            sink(canOpenFailure)
            return true
        }

        override fun parentUri(documentUri: String): String? = rootDoc
    }

    private fun provider(roots: Roots) = LocalProvider(
        MediaServer(
            id = "srv-local", name = "本地", type = ServerType.LOCAL,
            baseUrl = tree, createdAtEpochMs = 0,
        ),
        roots,
    )

    private fun videoItem() = MediaItem(
        "srv-local", video, MediaType.VIDEO, "video", path = video, parentId = rootDoc,
    )

    /** 断言操作抛出 DirectoryAccessRequired（而不是崩溃 / 其它异常）。 */
    private fun assertDirectoryAccessRequired(label: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("$label 必须抛出 DirectoryAccessRequired")
        } catch (e: ProviderException) {
            assertTrue(
                "$label 必须映射为 DirectoryAccessRequired（实际：$e）",
                e is ProviderException.DirectoryAccessRequired,
            )
        }
    }

    @Test
    fun `metadata failures map to directory access required`() {
        for (error in listOf(
            { IllegalStateException("provider died") },
            { SecurityException("grant revoked") },
            { IllegalArgumentException("bad document id") },
            { FileNotFoundException("document gone") },
        )) {
            val roots = Roots().apply { metadataFailure = error }
            assertDirectoryAccessRequired("listFolder(metadata)") {
                provider(roots).listFolder(null, PageRequest())
            }
            assertDirectoryAccessRequired("getItemDetail(metadata)") {
                provider(roots).getItemDetail(video)
            }
        }
    }

    @Test
    fun `child listing failures map to directory access required`() {
        for (error in listOf(
            { IllegalStateException("provider died") },
            { IllegalArgumentException("bad children uri") },
            { FileNotFoundException("directory gone") },
        )) {
            val roots = Roots().apply { listFailure = error }
            assertDirectoryAccessRequired("listFolder(listChildren)") {
                provider(roots).listFolder(null, PageRequest())
            }
            assertDirectoryAccessRequired("discoverSubtitles(listChildren)") {
                provider(roots).discoverSubtitles(videoItem())
            }
        }
    }

    @Test
    fun `canOpen failures map to directory access required`() {
        val roots = Roots().apply { canOpenFailure = { FileNotFoundException("document gone") } }
        assertDirectoryAccessRequired("resolvePlayback(canOpen)") {
            provider(roots).resolvePlayback(videoItem(), PlaybackOptions())
        }
    }

    @Test
    fun `connection reports an unavailable status instead of throwing`() = runBlocking {
        val roots = Roots().apply { metadataFailure = { IllegalStateException("provider died") } }
        val status = provider(roots).testConnection()
        assertFalse(status.ok)
        assertEquals(ProviderException.ErrorCode.CONNECTION, status.errorCode)
    }

    @Test
    fun `business not found is not swallowed into directory access required`() {
        val roots = Roots()
        val foreign = "content://other.documents/tree/other/document/file"
        try {
            runBlocking { provider(roots).getItemDetail(foreign) }
            fail("外部文档必须被拒绝")
        } catch (e: ProviderException) {
            assertTrue("NotFound 不得被改写成 DirectoryAccessRequired（实际：$e）",
                e is ProviderException.NotFound)
        }
    }

    @Test
    fun `healthy document access keeps working`() = runBlocking {
        val roots = Roots()
        val p = provider(roots)
        val listed = p.listFolder(null, PageRequest())
        assertEquals(listOf(video), listed.items.map { it.id })
        assertEquals("video/x-matroska", p.resolvePlayback(videoItem(), PlaybackOptions()).mimeType)
        assertEquals(video, p.getItemDetail(video).item.id)
    }
}
