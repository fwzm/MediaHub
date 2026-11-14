package com.mediahub.core.download

import com.mediahub.model.PlaybackMode
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 条目 61：下载完成 / 已存在的内容能被识别为可直接播放的本地源。 */
class DownloadedPlaybackAssociationTest {

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
    fun `completed download resolves to direct play file source`() = runBlocking {
        server.enqueue(MockResponse().setBody("video-bytes"))
        val manager = DownloadManager(root)
        manager.download(request("i1"))

        val source = manager.resolveLocalPlayback("s1", "i1")

        assertNotNull(source)
        assertEquals(PlaybackMode.DIRECT_PLAY, source?.mode)
        assertTrue(source?.url?.startsWith("file:") == true)
        assertTrue(source?.url?.endsWith("movie.mp4") == true)
        assertEquals("video/mp4", source?.mimeType)
        assertEquals("mp4", source?.container)
    }

    @Test
    fun `resolved local playback survives manager restart`() = runBlocking {
        server.enqueue(MockResponse().setBody("video-bytes"))
        DownloadManager(root).download(request("i1"))

        val source = DownloadManager(root).resolveLocalPlayback("s1", "i1")

        assertNotNull(source)
        assertTrue(source?.url?.startsWith("file:") == true)
    }

    @Test
    fun `pre existing file without index is recognized and healed`() = runBlocking {
        val manager = DownloadManager(root)
        val existing = manager.expectedFile("s1", "i2", "existing.mkv")
        existing.parentFile.mkdirs()
        existing.writeBytes("already-here".toByteArray())

        val source = manager.resolveLocalPlayback("s1", "i2", fileNameHint = "existing.mkv")

        assertNotNull(source)
        assertTrue(source?.url?.endsWith("existing.mkv") == true)
        assertEquals(DownloadStatus.COMPLETED, manager.record("s1", "i2")?.status)
    }

    @Test
    fun `partial or empty file is never exposed as playable`() = runBlocking {
        val manager = DownloadManager(root)
        manager.partialFile("s1", "i3", "movie.mp4").parentFile.mkdirs()
        manager.partialFile("s1", "i3", "movie.mp4").writeBytes("half".toByteArray())

        assertNull(manager.resolveLocalPlayback("s1", "i3", fileNameHint = "movie.mp4"))
    }

    @Test
    fun `deleted download no longer resolves`() = runBlocking {
        server.enqueue(MockResponse().setBody("video-bytes"))
        val manager = DownloadManager(root)
        manager.download(request("i4"))
        manager.delete("s1", "i4")

        assertNull(manager.resolveLocalPlayback("s1", "i4", fileNameHint = "movie.mp4"))
    }

    @Test
    fun `listCompleted only returns playable completed records`() = runBlocking {
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setResponseCode(500))
        val manager = DownloadManager(root)
        manager.download(request("ok"))
        manager.download(request("bad"))

        val completed = manager.listCompleted()

        assertEquals(listOf("movie.mp4"), completed.map { it.fileName })
        assertEquals("ok", completed.single().key.itemId)
    }

    private fun request(itemId: String) = DownloadRequest(
        serverId = "s1",
        itemId = itemId,
        url = server.url("/movie.mp4").toString(),
        fileName = "movie.mp4",
    )
}
