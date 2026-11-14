package com.mediahub.core.download

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 条目 18：删除下载缓存时最终文件 / .part / 索引一并清理，重复删除幂等。 */
class DownloadCacheCleanupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = temp.newFolder("downloads")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `delete removes final file partial file index and is idempotent`() = runBlocking {
        server.enqueue(MockResponse().setBody("payload"))
        val manager = DownloadManager(root)
        manager.download(request("i1"))

        val final = manager.expectedFile("s1", "i1", "movie.mp4")
        val partial = manager.partialFile("s1", "i1", "movie.mp4")
        // 模拟历史残留（同一 key 前缀的临时文件）。
        partial.writeBytes("leftover".toByteArray())
        val stray = File(final.parentFile, final.name.substringBefore('_') + "_stray.tmp")
        stray.writeText("stray")

        assertTrue(manager.delete("s1", "i1"))
        assertFalse(final.exists())
        assertFalse(partial.exists())
        assertFalse(stray.exists())
        assertNull(manager.record("s1", "i1"))
        assertFalse(File(root, "downloads.json").readText().contains("i1"))

        // 幂等：第二次删除返回 false，不抛异常。
        assertFalse(manager.delete("s1", "i1"))
        assertEquals(0, manager.cleanupOrphans())
    }

    @Test
    fun `delete cleans failed download partial and index`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val manager = DownloadManager(root)
        manager.download(request("i2"))
        val partial = manager.partialFile("s1", "i2", "movie.mp4")
        partial.parentFile.mkdirs()
        partial.writeBytes("half".toByteArray())

        assertTrue(manager.delete("s1", "i2"))
        assertFalse(partial.exists())
        assertNull(manager.record("s1", "i2"))
        assertFalse(manager.delete("s1", "i2"))
    }

    @Test
    fun `cleanup orphan files keeps referenced download`() = runBlocking {
        server.enqueue(MockResponse().setBody("keep-me"))
        val manager = DownloadManager(root)
        manager.download(request("i3"))
        val dir = manager.expectedFile("s1", "i3", "movie.mp4").parentFile
        val orphanPart = File(dir, "deadbeef_orphan.mp4.part").apply { writeBytes(byteArrayOf(1)) }
        val orphanFinal = File(dir, "deadbeef_orphan.mp4").apply { writeBytes(byteArrayOf(2)) }

        assertEquals(2, manager.cleanupOrphans())
        assertFalse(orphanPart.exists())
        assertFalse(orphanFinal.exists())
        assertEquals("keep-me", manager.expectedFile("s1", "i3", "movie.mp4").readText())
    }

    @Test
    fun `clearAll removes every download and index entry`() = runBlocking {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val manager = DownloadManager(root)
        manager.download(request("i4"))
        manager.download(request("i5"))
        assertTrue(manager.records.value.isNotEmpty())

        manager.clearAll()

        assertTrue(manager.records.value.isEmpty())
        manager.expectedFile("s1", "i4", "movie.mp4").parentFile.listFiles().orEmpty().forEach {
            assertFalse(it.isFile)
        }
        assertEquals(DownloadManager(root).records.value.size, 0)
    }

    private fun request(itemId: String) = DownloadRequest(
        serverId = "s1",
        itemId = itemId,
        url = server.url("/movie.mp4").toString(),
        fileName = "movie.mp4",
    )
}
