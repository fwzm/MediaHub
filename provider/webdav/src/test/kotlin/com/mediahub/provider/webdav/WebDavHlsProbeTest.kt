package com.mediahub.provider.webdav

import com.mediahub.model.HlsContentDetector
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackOptions
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 条目 136：无扩展名 HLS 地址的识别。
 *
 * 两层契约：
 * 1. [WebDavApi.probePlaylistMime] 只看真实响应（Content-Type / `#EXTM3U` 前缀），
 *    失败必须是"返回 null"而不是把播放流程打断；
 * 2. [WebDavPlaybackProvider] 仅在**容器未知**时探测，已知容器绝不额外发请求。
 */
class WebDavHlsProbeTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
    }

    @After
    fun tearDown() {
        try {
            webServer.shutdown()
        } catch (ignored: Exception) {
        }
    }

    private suspend fun storePasswordAndAuth(): String {
        stack.storePassword()
        return stack.session.authorization()
    }

    // ---- 探测层 ----

    @Test
    fun `probe recognizes hls from the content type and sends a bounded range request`() = runBlocking {
        val auth = storePasswordAndAuth()
        webServer.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n"),
        )

        val mime = stack.api.probePlaylistMime(webServer.url("/dav/channel").toString(), auth)

        assertEquals(HlsContentDetector.MIME_TYPE, mime)
        val request = webServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=0-1023", request.getHeader("Range"))
        assertEquals(auth, request.getHeader("Authorization"))
    }

    @Test
    fun `probe recognizes hls from the playlist marker under a generic content type`() = runBlocking {
        val auth = storePasswordAndAuth()
        webServer.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n"),
        )

        assertEquals(
            HlsContentDetector.MIME_TYPE,
            stack.api.probePlaylistMime(webServer.url("/dav/channel").toString(), auth),
        )
    }

    @Test
    fun `probe returns null for non playlist content`() = runBlocking {
        val auth = storePasswordAndAuth()
        webServer.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("\u0000\u0000\u0000\u0018ftypisom"),
        )

        assertNull(stack.api.probePlaylistMime(webServer.url("/dav/channel").toString(), auth))
    }

    @Test
    fun `probe failure degrades to null instead of breaking playback resolution`() = runBlocking {
        val auth = storePasswordAndAuth()
        webServer.enqueue(MockResponse().setResponseCode(404))

        assertNull(
            "非 2xx 只是探测失败，不得抛异常",
            stack.api.probePlaylistMime(webServer.url("/dav/channel").toString(), auth),
        )
    }

    @Test
    fun `probe network failure degrades to null`() = runBlocking {
        stack.storePassword()
        webServer.shutdown()
        assertNull(
            stack.api.probePlaylistMime(webServer.url("/dav/channel").toString(), authorization = null),
        )
    }

    // ---- Provider 接线层 ----

    private fun itemFor(path: String, container: String?) = MediaItem(
        serverId = WebDavTestStack.SERVER_ID,
        id = path,
        type = MediaType.VIDEO,
        title = "channel",
        path = path,
        container = container,
    )

    @Test
    fun `unknown container probes once and uses the detected hls mime`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/channel").toString()
        var calls = 0
        var probedUrl: String? = null
        var probedAuth: String? = null

        val provider = WebDavPlaybackProvider(stack.server, stack.session, stack.logger) { url, auth ->
            calls++
            probedUrl = url
            probedAuth = auth
            HlsContentDetector.MIME_TYPE
        }

        val source = provider.resolvePlayback(itemFor(target, container = null), PlaybackOptions())

        assertEquals(HlsContentDetector.MIME_TYPE, source.mimeType)
        assertEquals(1, calls)
        assertEquals(target, probedUrl)
        assertEquals(stack.expectedBasicHeader, probedAuth)
        assertEquals("探测本身不应产生额外的真实请求（此处由替身完成）", 0, webServer.requestCount)
    }

    @Test
    fun `known container never probes`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/movie.mkv").toString()
        val provider = WebDavPlaybackProvider(stack.server, stack.session, stack.logger) { _, _ ->
            fail("已知容器不得发起探测")
            null
        }

        val source = provider.resolvePlayback(itemFor(target, container = "mkv"), PlaybackOptions())

        assertEquals("video/x-matroska", source.mimeType)
    }

    @Test
    fun `m3u8 container maps to hls mime without probing`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/live.m3u8").toString()
        val provider = WebDavPlaybackProvider(stack.server, stack.session, stack.logger) { _, _ ->
            fail("扩展名已能确定 HLS，不得再探测")
            null
        }

        val source = provider.resolvePlayback(itemFor(target, container = "m3u8"), PlaybackOptions())

        assertEquals(HlsContentDetector.MIME_TYPE, source.mimeType)
    }

    @Test
    fun `probe returning null keeps the previous unknown mime behavior`() = runBlocking {
        stack.storePassword()
        val target = webServer.url("/dav/channel").toString()
        val provider = WebDavPlaybackProvider(stack.server, stack.session, stack.logger) { _, _ -> null }

        val source = provider.resolvePlayback(itemFor(target, container = null), PlaybackOptions())

        assertNull(source.mimeType)
        assertEquals(target, source.url)
    }
}
