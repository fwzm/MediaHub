package com.mediahub.core.download

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 下载状态机：QUEUED → RUNNING → COMPLETED/FAILED/PAUSED/CANCELLED，且每次流转持久化。 */
class DownloadStateMachineTest {

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
    fun `download reports queued running completed and persists across instances`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello world"))
        val manager = DownloadManager(root)
        val statuses = CopyOnWriteArrayList<DownloadStatus>()

        val record = manager.download(request("i1"), onProgress = { statuses += it.status })

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals(11L, record.sizeBytes)
        assertEquals(
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.COMPLETED),
            statuses.collapse(),
        )
        assertEquals("hello world", manager.expectedFile("s1", "i1", "movie.mp4").readText())
        assertFalse(manager.partialFile("s1", "i1", "movie.mp4").exists())
        assertEquals(1, server.requestCount)

        val reloaded = DownloadManager(root)
        val persisted = reloaded.record("s1", "i1")
        assertEquals(DownloadStatus.COMPLETED, persisted?.status)
        assertEquals(11L, persisted?.sizeBytes)
        assertEquals("s1", persisted?.key?.serverId)
        assertEquals("i1", persisted?.key?.itemId)
    }

    @Test
    fun `http failure marks failed and keeps no completed file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val manager = DownloadManager(root)
        val statuses = CopyOnWriteArrayList<DownloadStatus>()

        val record = manager.download(request("i2"), onProgress = { statuses += it.status })

        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals(DownloadFailureKind.HTTP, record.failureKind)
        assertEquals(
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FAILED),
            statuses.collapse(),
        )
        assertFalse(manager.expectedFile("s1", "i2", "movie.mp4").exists())
    }

    @Test
    fun `pause keeps partial and can resume`() = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789").setBodyDelay(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        val manager = DownloadManager(root)

        val job = launch(Dispatchers.IO) { manager.download(request("i3")) }
        waitUntil { manager.record("s1", "i3")?.status == DownloadStatus.RUNNING }
        manager.pause("s1", "i3")
        job.join()

        assertEquals(DownloadStatus.PAUSED, manager.record("s1", "i3")?.status)

        val resumed = manager.download(request("i3"))
        assertEquals(DownloadStatus.COMPLETED, resumed.status)
        assertEquals("0123456789", manager.expectedFile("s1", "i3", "movie.mp4").readText())
    }

    @Test
    fun `cancel removes partial and marks cancelled`() = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789").setBodyDelay(3, TimeUnit.SECONDS))
        val manager = DownloadManager(root)

        val job = launch(Dispatchers.IO) { manager.download(request("i4")) }
        waitUntil { manager.record("s1", "i4")?.status == DownloadStatus.RUNNING }
        manager.cancel("s1", "i4")
        job.join()

        assertEquals(DownloadStatus.CANCELLED, manager.record("s1", "i4")?.status)
        assertFalse(manager.partialFile("s1", "i4", "movie.mp4").exists())
        assertFalse(manager.expectedFile("s1", "i4", "movie.mp4").exists())
    }

    @Test
    fun `index file never stores urls headers or tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("payload"))
        val manager = DownloadManager(root)
        val request = DownloadRequest(
            serverId = "s1",
            itemId = "i5",
            url = server.url("/movie.mp4?token=fixture-token").toString(),
            fileName = "movie.mp4",
            headers = mapOf("Authorization" to "Bearer fixture-token"),
        )

        manager.download(request)

        val indexText = java.io.File(root, "downloads.json").readText()
        assertFalse(indexText.contains("fixture-token"))
        assertFalse(indexText.contains("Authorization"))
        assertFalse(indexText.contains("http"))
    }

    private fun request(itemId: String) = DownloadRequest(
        serverId = "s1",
        itemId = itemId,
        url = server.url("/movie.mp4").toString(),
        fileName = "movie.mp4",
    )

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待状态超时" }
            delay(10)
        }
    }

    private fun List<DownloadStatus>.collapse(): List<DownloadStatus> =
        filterIndexed { index, status -> index == 0 || this[index - 1] != status }
}
