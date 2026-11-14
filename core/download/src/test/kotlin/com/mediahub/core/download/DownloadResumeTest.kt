package com.mediahub.core.download

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 断点续传：Range/206 追加、200 回退整包、416 已有完整片段。 */
class DownloadResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var root: java.io.File

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
    fun `resumes existing partial with Range and appends remaining bytes`() = runBlocking {
        val manager = DownloadManager(root)
        val partial = manager.partialFile("s1", "i1", "movie.mp4")
        partial.parentFile.mkdirs()
        partial.writeBytes("0123".toByteArray())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("456789"),
        )

        val record = manager.download(request("i1"))

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertEquals("0123456789", manager.expectedFile("s1", "i1", "movie.mp4").readText())
        assertFalse(partial.exists())
    }

    @Test
    fun `restarts from zero when server ignores Range`() = runBlocking {
        val manager = DownloadManager(root)
        val partial = manager.partialFile("s1", "i2", "movie.mp4")
        partial.parentFile.mkdirs()
        partial.writeBytes("BROKEN".toByteArray())

        server.enqueue(MockResponse().setResponseCode(200).setBody("full-content"))

        val record = manager.download(request("i2"))

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        assertEquals("full-content", manager.expectedFile("s1", "i2", "movie.mp4").readText())
        assertFalse(partial.exists())
    }

    @Test
    fun `416 with already complete partial finalizes without refetch`() = runBlocking {
        val manager = DownloadManager(root)
        val partial = manager.partialFile("s1", "i3", "movie.mp4")
        partial.parentFile.mkdirs()
        partial.writeBytes("0123456789".toByteArray())

        server.enqueue(MockResponse().setResponseCode(416))

        val record = manager.download(request("i3", expectedSize = 10))

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("0123456789", manager.expectedFile("s1", "i3", "movie.mp4").readText())
        assertFalse(partial.exists())
    }

    @Test
    fun `mismatched content range start discards partial instead of corrupting`() = runBlocking {
        val manager = DownloadManager(root)
        val partial = manager.partialFile("s1", "i4", "movie.mp4")
        partial.parentFile.mkdirs()
        partial.writeBytes("0123".toByteArray())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-9/10")
                .setBody("23456789"),
        )

        val record = manager.download(request("i4"))

        assertEquals(DownloadStatus.FAILED, record.status)
        assertFalse(manager.expectedFile("s1", "i4", "movie.mp4").exists())
    }

    @Test
    fun `expected size mismatch fails without publishing a completed file`() = runBlocking {
        val manager = DownloadManager(root)
        server.enqueue(MockResponse().setBody("short"))

        val record = manager.download(request("i5", expectedSize = 100))

        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals(DownloadFailureKind.NETWORK, record.failureKind)
        assertFalse(manager.expectedFile("s1", "i5", "movie.mp4").exists())
    }

    private fun request(itemId: String, expectedSize: Long? = null) = DownloadRequest(
        serverId = "s1",
        itemId = itemId,
        url = server.url("/movie.mp4").toString(),
        fileName = "movie.mp4",
        expectedSizeBytes = expectedSize,
    )
}
