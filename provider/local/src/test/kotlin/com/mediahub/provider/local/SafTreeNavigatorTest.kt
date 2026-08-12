package com.mediahub.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 树导航（review P1）：全程 tree-backed，多层子目录可导航，文件可播放。
 * 基于纯字符串 SafUri 实现（生产与测试共用同一路径），fake SafSource 验证数据流。
 */
class SafTreeNavigatorTest {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"

    private class FakeSource : SafTreeNavigator.SafSource {
        private val data = mapOf(
            "primary:Movies" to listOf(
                SafTreeNavigator.SafRow("primary:Movies/Sub", "Sub", "vnd.android.document/directory", null),
                SafTreeNavigator.SafRow("primary:Movies/a.mp4", "a.mp4", "video/mp4", 100L),
            ),
            "primary:Movies/Sub" to listOf(
                SafTreeNavigator.SafRow("primary:Movies/Sub/b.mkv", "b.mkv", "video/x-matroska", 200L),
            ),
        )

        override fun listChildren(childrenUri: String): List<SafTreeNavigator.SafRow> {
            // 从 children uri 提取父 docId（与生产 ContentResolver 行为等价）
            val parentDocId = SafUri.docId(childrenUri.substringBefore("/children"))
            return data[parentDocId] ?: emptyList()
        }

        override fun canOpen(documentUri: String): Boolean = documentUri.contains(".mp4")
    }

    @Test
    fun `root lists tree-backed children with document uris`() {
        val nav = SafTreeNavigator(treeUri, FakeSource())
        val entries = nav.listChildren(nav.rootDocId())

        assertEquals(2, entries.size)
        assertEquals("primary:Movies", nav.rootDocId())
        // 子项必须是 tree-backed document uri（可在子目录继续导航），而非 single-document
        val dirUri = entries[0].uri
        assertTrue("tree-backed: $dirUri", dirUri.contains("/tree/primary%3AMovies/document/primary%3AMovies%2FSub"))
        assertTrue(entries[0].mimeType == "vnd.android.document/directory")
    }

    @Test
    fun `nested folder can be navigated via its docId`() {
        val nav = SafTreeNavigator(treeUri, FakeSource())
        val root = nav.listChildren(nav.rootDocId())
        val subDir = root.first { it.mimeType == "vnd.android.document/directory" }

        // 进入子目录：从已保存的 document uri 提取 docId，再列子项
        val subDocId = nav.docIdOf(subDir.uri)
        val children = nav.listChildren(subDocId)

        assertEquals(1, children.size)
        assertEquals("b.mkv", children[0].name)
        val childUri = children[0].uri
        assertTrue("deep tree-backed: $childUri", childUri.contains("/document/primary%3AMovies%2FSub%2Fb.mkv"))
    }

    @Test
    fun `file entry can be opened for playback`() {
        val nav = SafTreeNavigator(treeUri, FakeSource())
        val root = nav.listChildren(nav.rootDocId())
        val file = root.first { it.mimeType != "vnd.android.document/directory" }
        assertTrue(FakeSource().canOpen(file.uri))
    }

    @Test
    fun `docId round trip preserves tree prefix`() {
        val nav = SafTreeNavigator(treeUri, FakeSource())
        val entries = nav.listChildren(nav.rootDocId())
        val dir = entries.first()
        assertEquals("primary:Movies/Sub", nav.docIdOf(dir.uri))
    }

    @Test
    fun `children uri encodes parent docId`() {
        val childrenUri = SafUri.childrenUri(treeUri, "primary:Movies/Sub")
        assertTrue(childrenUri, childrenUri.endsWith("/document/primary%3AMovies%2FSub/children"))
        assertEquals("primary:Movies/Sub", SafUri.docId(childrenUri.substringBefore("/children")))
    }
}
