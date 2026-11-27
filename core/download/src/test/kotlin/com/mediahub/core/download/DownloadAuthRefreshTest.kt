package com.mediahub.core.download

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 条目 94/95：网盘登录过期 / 临时签名 URL 失效时的重授权与重解析。
 *
 * - 401/403 → 调用 DownloadSourceResolver 强制刷新一次，成功后继续下载；
 * - 刷新后仍 401/403 → FAILED(AUTH_REQUIRED)，交由 UI 触发重新授权；
 * - 已知过期时间 → 发请求前主动刷新，不做必然失败的请求。
 */
class DownloadAuthRefreshTest {

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
    fun `403 triggers one forced resolve and download continues`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("fresh-content"))
        val resolver = ScriptedResolver(
            ResolvedDownloadSource(server.url("/old").toString()),
            ResolvedDownloadSource(server.url("/new").toString()),
        )
        val manager = DownloadManager(root, sourceResolver = resolver)

        val record = manager.download(request("i1"))

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals("fresh-content", manager.expectedFile("s1", "i1", "movie.mp4").readText())
        assertEquals(listOf(false, true), resolver.forceRefreshCalls)
        assertEquals("/old", server.takeRequest().path)
        assertEquals("/new", server.takeRequest().path)
    }

    @Test
    fun `persistent 401 after refresh becomes auth required`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val resolver = ScriptedResolver(
            ResolvedDownloadSource(server.url("/old").toString()),
            ResolvedDownloadSource(server.url("/new").toString()),
        )
        val manager = DownloadManager(root, sourceResolver = resolver)

        val record = manager.download(request("i2"))

        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals(DownloadFailureKind.AUTH_REQUIRED, record.failureKind)
        assertEquals(listOf(false, true), resolver.forceRefreshCalls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `expired signed url is refreshed before first request`() = runBlocking {
        server.enqueue(MockResponse().setBody("fresh-content"))
        val resolver = ScriptedResolver(
            ResolvedDownloadSource(server.url("/expired").toString(), expiresAtEpochMs = 500),
            ResolvedDownloadSource(server.url("/fresh").toString(), expiresAtEpochMs = 10_000),
        )
        val manager = DownloadManager(root, sourceResolver = resolver, clock = { 1_000 })

        val record = manager.download(request("i3"))

        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertEquals(listOf(false, true), resolver.forceRefreshCalls)
        assertEquals(1, server.requestCount)
        assertEquals("/fresh", server.takeRequest().path)
    }

    @Test
    fun `401 without resolver is auth required`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val manager = DownloadManager(root)

        val record = manager.download(request("i4"))

        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals(DownloadFailureKind.AUTH_REQUIRED, record.failureKind)
        assertEquals(1, server.requestCount)
    }

    private fun request(itemId: String) = DownloadRequest(
        serverId = "s1",
        itemId = itemId,
        url = server.url("/movie.mp4").toString(),
        fileName = "movie.mp4",
    )

    private class ScriptedResolver(
        private val first: ResolvedDownloadSource,
        private val second: ResolvedDownloadSource,
    ) : DownloadSourceResolver {
        val forceRefreshCalls = CopyOnWriteArrayList<Boolean>()

        override suspend fun resolve(
            request: DownloadRequest,
            forceRefresh: Boolean,
        ): ResolvedDownloadSource {
            forceRefreshCalls += forceRefresh
            return if (forceRefreshCalls.size == 1) first else second
        }
    }
}
