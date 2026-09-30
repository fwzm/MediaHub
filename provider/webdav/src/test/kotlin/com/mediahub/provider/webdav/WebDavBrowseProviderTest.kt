package com.mediahub.provider.webdav

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackOptions
import com.mediahub.provider.api.ProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WebDAV 目录浏览与直链播放的线级契约。 */
class WebDavBrowseProviderTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun enqueueMultistatus(vararg responses: String) {
        webServer.enqueue(
            MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                .setBody(WebDavFixtures.multistatus(*responses))
        )
    }

    private fun RecordedRequest.authHeader(): String? = getHeader("Authorization")
    private fun RecordedRequest.depth(): String? = getHeader("Depth")

    @Test
    fun `list folder issues PROPFIND depth 1 with basic auth and propfind body`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("/dav/movie.mkv", length = 10, displayName = "movie.mkv"),
        )

        val result = stack.browseProvider().listFolder(null, PageRequest(offset = 0, limit = 50))

        val request = webServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.depth())
        assertEquals(stack.expectedBasicHeader, request.authHeader())
        assertTrue(request.body.readUtf8().contains("<D:propfind"))
        assertEquals(1, result.items.size)
        assertEquals("movie.mkv", result.items[0].title)
    }

    @Test
    fun `self entry is removed and folders sort first`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("/dav/zebra.mkv", length = 1, displayName = "zebra.mkv"),
            WebDavFixtures.collection("/dav/Shows/", "Shows"),
        )

        val result = stack.browseProvider().listFolder(null, PageRequest())

        assertEquals(listOf("Shows", "zebra.mkv"), result.items.map { it.title })
        assertEquals(MediaType.FOLDER, result.items[0].type)
        assertEquals(MediaType.VIDEO, result.items[1].type)
        assertTrue(result.items[0].type.isContainer)
    }

    @Test
    fun `href without trailing slash but collection resourcetype is a folder`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.collection("/dav/Shows", "Shows"),
        )

        val result = stack.browseProvider().listFolder(null, PageRequest())
        assertEquals(MediaType.FOLDER, result.items.single().type)
        assertNull("目录不得带 sizeBytes", result.items.single().sizeBytes)
    }

    @Test
    fun `percent encoded cjk and space hrefs are decoded for display`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("/dav/%E6%88%91%E7%9A%84%20%E7%94%B5%E5%BD%B1.mkv", length = 5),
        )

        val result = stack.browseProvider().listFolder(null, PageRequest())
        assertEquals("我的 电影.mkv", result.items.single().title)
        // 播放地址必须保留原始编码形式（服务器按编码路径解析）。
        assertTrue(result.items.single().path!!.contains("%E6%88%91"))
    }

    @Test
    fun `cross origin hrefs are dropped`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("https://evil.example/steal.mkv", length = 1, displayName = "steal.mkv"),
            WebDavFixtures.file("/dav/ok.mkv", length = 1, displayName = "ok.mkv"),
        )

        val result = stack.browseProvider().listFolder(null, PageRequest())
        assertEquals(listOf("ok.mkv"), result.items.map { it.title })
    }

    @Test
    fun `local slicing reports honest paging without claiming server paging`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("/dav/a.mkv", length = 1, displayName = "a.mkv"),
            WebDavFixtures.file("/dav/b.mkv", length = 1, displayName = "b.mkv"),
            WebDavFixtures.file("/dav/c.mkv", length = 1, displayName = "c.mkv"),
        )
        // 单次 PROPFIND 返回整个目录；两页共用同一响应（本地切片）。
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "根"),
            WebDavFixtures.file("/dav/a.mkv", length = 1, displayName = "a.mkv"),
            WebDavFixtures.file("/dav/b.mkv", length = 1, displayName = "b.mkv"),
            WebDavFixtures.file("/dav/c.mkv", length = 1, displayName = "c.mkv"),
        )

        val first = stack.browseProvider().listFolder(null, PageRequest(offset = 0, limit = 2))
        assertEquals(listOf("a.mkv", "b.mkv"), first.items.map { it.title })
        assertEquals(3, first.totalCount)
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)

        val second = stack.browseProvider().listFolder(null, PageRequest(offset = 2, limit = 2))
        assertEquals(listOf("c.mkv"), second.items.map { it.title })
        assertFalse(second.hasMore)
        assertNull(second.nextOffset)
    }

    @Test
    fun `401 maps to auth expired`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching {
            stack.browseProvider().listFolder(null, PageRequest())
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.AuthExpired)
    }

    @Test
    fun `404 maps to not found`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(MockResponse().setResponseCode(404))
        val failure = runCatching {
            stack.browseProvider().listFolder(null, PageRequest())
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.NotFound)
    }

    @Test
    fun `302 is surfaced as error and never followed`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example/redirect")
        )
        val failure = runCatching {
            stack.browseProvider().listFolder(null, PageRequest())
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.Http)
        assertEquals("重定向不得被跟随（凭据不外流）", 1, webServer.requestCount)
        assertEquals("302", (failure as ProviderException.Http).statusCode.toString())
    }

    @Test
    fun `missing credentials fails closed without any request`() = runBlocking {
        // 不调用 storePassword(): 未登录状态
        val failure = runCatching {
            stack.browseProvider().listFolder(null, PageRequest())
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.AuthRequired)
        assertEquals("不得以匿名请求代替会话", 0, webServer.requestCount)
    }

    // ---- 播放 ----

    private fun itemFor(path: String, type: MediaType = MediaType.VIDEO) = MediaItem(
        serverId = WebDavTestStack.SERVER_ID,
        id = path,
        type = type,
        title = "t",
        path = path,
        container = "mkv",
    )

    @Test
    fun `resolve playback returns direct play url with header only auth`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/movie.mkv").toString()
        val source = stack.playbackProvider().resolvePlayback(itemFor(target), PlaybackOptions())

        assertEquals(target, source.url)
        assertEquals(stack.expectedBasicHeader, source.headers["Authorization"])
        assertTrue(source.isDirectPlay)
        assertNull("WebDAV 无服务端会话，不得伪造 sessionId", source.sessionId)
        assertNull("地址中不得出现 user-info", java.net.URI(source.url).userInfo)
    }

    @Test
    fun `url with embedded credentials is refused`() = runBlocking {
        stack.storePassword()
        val target = "http://alice:pw@localhost:${webServer.port}/dav/movie.mkv"
        val failure = runCatching {
            stack.playbackProvider().resolvePlayback(itemFor(target), PlaybackOptions())
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.Parse)
    }

    @Test
    fun `cross origin playback target is refused`() = runBlocking {
        stack.storePassword()
        val failure = runCatching {
            stack.playbackProvider().resolvePlayback(
                itemFor("https://evil.example/movie.mkv"),
                PlaybackOptions(),
            )
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.Parse)
    }

    @Test
    fun `folder is not playable`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/Shows/").toString()
        val failure = runCatching {
            stack.playbackProvider().resolvePlayback(
                itemFor(target, MediaType.FOLDER),
                PlaybackOptions(),
            )
        }.exceptionOrNull()
        assertTrue("实际：$failure", failure is ProviderException.NotYetImplemented)
    }

    @Test
    fun `playback falls back to item id when snapshot item has no path`() = runBlocking {
        // PlayerViewModel 快照重建的 MediaItem 不带 path（详情→播放快照契约），
        // 播放目标必须兜底用 id，不得 NPE 或解析为相对路径。
        stack.storePassword()
        val target = webServer.url("/dav/movie.mkv").toString()
        val snapshotItem = MediaItem(
            serverId = WebDavTestStack.SERVER_ID,
            id = target,
            type = MediaType.VIDEO,
            title = "movie.mkv",
            path = null,
            container = "mkv",
        )
        val source = stack.playbackProvider().resolvePlayback(snapshotItem, PlaybackOptions())
        assertEquals(target, source.url)
        assertEquals(stack.expectedBasicHeader, source.headers["Authorization"])
        assertTrue(source.isDirectPlay)
    }
}
