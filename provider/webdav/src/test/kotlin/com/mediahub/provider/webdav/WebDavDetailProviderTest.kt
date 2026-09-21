package com.mediahub.provider.webdav

import com.mediahub.model.MediaType
import com.mediahub.provider.api.ProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDAV 文件详情（PROPFIND Depth:0）契约。
 *
 * 覆盖：Depth:0 + Basic 认证打在文件本身（不补尾部 `/`）、中/空格/+/百分号/# 编码
 * 路径的往返、同 origin 边界（跨 origin 与 user-info 拒绝且**零网络请求**）、
 * 目录拒绝、404/401/5xx 错误分型、空 multistatus、无元数据降级。
 */
class WebDavDetailProviderTest {

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

    private fun detailProvider() = WebDavDetailProvider(stack.server, stack.api, stack.session)

    private fun RecordedRequest.depth(): String? = getHeader("Depth")

    @Test
    fun `file detail issues PROPFIND depth 0 on the file itself with basic auth`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(
            WebDavFixtures.file("/dav/movie.mkv", length = 123_456, displayName = "movie.mkv"),
        )

        val target = webServer.url("/dav/movie.mkv").toString()
        val detail = detailProvider().getItemDetail(target)

        val request = webServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.depth())
        assertEquals(stack.expectedBasicHeader, request.getHeader("Authorization"))
        assertEquals("Depth:0 必须打在文件本身，不得补尾部 `/`", "/dav/movie.mkv", request.path)

        val item = detail.item
        assertEquals(stack.server.id, item.serverId)
        assertEquals(target, item.id)
        assertEquals("movie.mkv", item.title)
        assertEquals(MediaType.VIDEO, item.type)
        assertEquals(123_456L, item.sizeBytes)
        assertEquals("mkv", item.container)
        assertEquals(target, item.path)
        // 裸文件系统：不得伪造元数据
        assertTrue("WebDAV 详情除 item 外必须为空", detail.seasons.isEmpty() && detail.versions.isEmpty() &&
            detail.streams.isEmpty() && detail.audioTracks.isEmpty() && detail.subtitles.isEmpty())
    }

    @Test
    fun `encoded cjk space plus and hash path round-trips without double decoding`() = runBlocking {
        stack.storePassword()
        // 服务器按 RFC 3986 返回已编码 href；itemId 保持编码形态原样打回。
        // 文件名含：中文、空格、+、字面 %（编码为 %25）、#（编码为 %23）。
        val encodedName = "%E7%94%B5%E5%BD%B1%20a%2Bb%2050%25%20%23test.mkv"
        enqueueMultistatus(
            WebDavFixtures.file("/dav/$encodedName", length = 7, displayName = null),
        )

        val target = webServer.url("/dav/$encodedName").toString()
        val detail = detailProvider().getItemDetail(target)

        val request = webServer.takeRequest()
        assertEquals(
            "itemId 必须原样（不解码也不二次编码）作为请求目标",
            encodedName,
            request.path?.substringAfterLast('/'),
        )
        // displayname 缺失 → 回落 href 末段 percent-decode：+ 不是空格、%25 是 %、%23 是 #
        assertEquals("电影 a+b 50% #test.mkv", detail.item.title)
        assertEquals(MediaType.VIDEO, detail.item.type)
    }

    @Test
    fun `multilevel directory path is preserved`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(WebDavFixtures.file("/dav/a/b/%E5%BD%B1/x.mkv", length = 1))
        val target = webServer.url("/dav/a/b/%E5%BD%B1/x.mkv").toString()

        val detail = detailProvider().getItemDetail(target)

        assertEquals("/dav/a/b/%E5%BD%B1/x.mkv", webServer.takeRequest().path)
        assertEquals("x.mkv", detail.item.title)
    }

    @Test
    fun `collection itemId is refused as not a movie`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus(WebDavFixtures.collection("/dav/Shows/", "Shows"))

        try {
            detailProvider().getItemDetail(webServer.url("/dav/Shows/").toString())
            throw AssertionError("目录不得产生影片详情")
        } catch (expected: ProviderException.NotFound) {
            assertNotNull(expected)
        }
    }

    @Test
    fun `cross origin itemId is refused before any network request`() = runBlocking {
        stack.storePassword()
        try {
            detailProvider().getItemDetail("http://evil.example:8080/dav/movie.mkv")
            throw AssertionError("跨 origin 引用必须拒绝")
        } catch (expected: ProviderException.NotFound) {
            // 凭据不得发往第二主机
        }
        assertEquals("拒绝必须发生在任何网络请求之前", 0, webServer.requestCount)
    }

    @Test
    fun `user-info itemId is refused before any network request`() = runBlocking {
        stack.storePassword()
        val withUserInfo = webServer.url("/dav/").toString()
            .replace("http://", "http://admin:Sup3rS3cret@")
        try {
            detailProvider().getItemDetail(withUserInfo)
            throw AssertionError("带 user-info 的引用必须拒绝")
        } catch (expected: ProviderException.NotFound) {
            assertNotNull(expected)
        }
        assertEquals(0, webServer.requestCount)
    }

    @Test
    fun `deleted file reports not found`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(MockResponse().setResponseCode(404))
        try {
            detailProvider().getItemDetail(webServer.url("/dav/gone.mkv").toString())
            throw AssertionError("404 必须映射为 NotFound")
        } catch (expected: ProviderException.NotFound) {
            assertNotNull(expected)
        }
    }

    @Test
    fun `auth expired reports session expiry`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(MockResponse().setResponseCode(401))
        try {
            detailProvider().getItemDetail(webServer.url("/dav/movie.mkv").toString())
            throw AssertionError("401 必须映射为 AuthExpired")
        } catch (expected: ProviderException.AuthExpired) {
            assertNotNull(expected)
        }
    }

    @Test
    fun `server error is distinguished from not found`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(MockResponse().setResponseCode(503))
        try {
            detailProvider().getItemDetail(webServer.url("/dav/movie.mkv").toString())
            throw AssertionError("5xx 必须映射为 Http")
        } catch (expected: ProviderException.Http) {
            assertEquals(503, expected.statusCode)
        }
    }

    @Test
    fun `empty depth 0 multistatus is a parse failure`() = runBlocking {
        stack.storePassword()
        enqueueMultistatus()
        try {
            detailProvider().getItemDetail(webServer.url("/dav/movie.mkv").toString())
            throw AssertionError("Depth:0 空响应必须报 Parse")
        } catch (expected: ProviderException.Parse) {
            assertNotNull(expected)
        }
    }
}
