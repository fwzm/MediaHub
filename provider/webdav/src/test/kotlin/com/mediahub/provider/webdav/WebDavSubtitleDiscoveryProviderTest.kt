package com.mediahub.provider.webdav

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.DiscoveredSubtitle
import com.mediahub.provider.api.MediaSubtitleDiscoveryProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDAV 同目录字幕发现（P2 字幕中心切片一）：
 * 扩展名过滤 / 语言尾缀 / 视频自身剔除 / 无字幕目录空列表。
 */
class WebDavSubtitleDiscoveryProviderTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack
    private lateinit var discovery: MediaSubtitleDiscoveryProvider

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
        discovery = WebDavSubtitleDiscoveryProvider(stack.server, stack.api, stack.session)
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private suspend fun ensureAuth() = stack.storePassword()

    private fun enqueueMultistatus(vararg responses: String) {
        webServer.enqueue(
            MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                .setBody(WebDavFixtures.multistatus(*responses))
        )
    }

    private fun videoItem(fileName: String = "movie.mkv"): MediaItem {
        val videoUrl = webServer.url("/dav/$fileName").toString()
        return MediaItem(
            serverId = WebDavTestStack.SERVER_ID,
            id = videoUrl,
            type = MediaType.VIDEO,
            title = fileName,
            path = videoUrl,
        )
    }

    private fun RecordedRequest.depth(): String? = getHeader("Depth")

    @Test
    fun `discovers same-folder subtitles with extension filter and language suffix`() = runBlocking {
        ensureAuth()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "dav"),
            WebDavFixtures.file("/dav/movie.mkv", length = 100, displayName = "movie.mkv"),
            WebDavFixtures.file("/dav/movie.zh.srt", length = 10, displayName = "movie.zh.srt"),
            WebDavFixtures.file("/dav/movie.eng.ass", length = 20, displayName = "movie.eng.ass"),
            WebDavFixtures.file("/dav/cover.jpg", length = 30, displayName = "cover.jpg"),
            WebDavFixtures.file("/dav/subs.zip", length = 40, displayName = "subs.zip"),
        )

        val found = discovery.discoverSubtitles(videoItem())

        val request = webServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.depth())
        // 目录打点：视频父目录（同 origin）
        assertTrue(request.path!!.startsWith("/dav/"))

        assertEquals(2, found.size)
        val srt = found.first { it.extension == "srt" }
        val ass = found.first { it.extension == "ass" }
        assertEquals("zh", srt.language)
        assertEquals("en", ass.language)
        assertEquals("movie.zh", srt.name)
        assertEquals("application/x-subrip", srt.mimeType)
        assertEquals("text/x-ssa", ass.mimeType)
        // id = uri = 同 origin 绝对 URL
        assertTrue(srt.id.endsWith("/dav/movie.zh.srt"))
        assertEquals(srt.id, srt.uri)
        // jpg/zip 被扩展名过滤
        assertTrue(found.none { it.extension == "jpg" || it.extension == "zip" })
    }

    @Test
    fun `video itself and folders are excluded`() = runBlocking {
        ensureAuth()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "dav"),
            WebDavFixtures.file("/dav/movie.mkv", length = 100, displayName = "movie.mkv"),
            WebDavFixtures.collection("/dav/movie.mkv/", "假目录"),
            WebDavFixtures.file("/dav/movie.srt", length = 10, displayName = "movie.srt"),
        )

        val found = discovery.discoverSubtitles(videoItem())

        assertEquals(1, found.size)
        assertEquals("movie", found.single().name)
    }

    @Test
    fun `folder without subtitles returns empty list`() = runBlocking {
        ensureAuth()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "dav"),
            WebDavFixtures.file("/dav/movie.mkv", length = 100, displayName = "movie.mkv"),
            WebDavFixtures.file("/dav/poster.jpg", length = 10, displayName = "poster.jpg"),
        )

        val found = discovery.discoverSubtitles(videoItem())

        assertTrue(found.isEmpty())
    }

    @Test
    fun `unknown language suffix stays null and vtt ssa extensions covered`() = runBlocking {
        ensureAuth()
        enqueueMultistatus(
            WebDavFixtures.collection("/dav/", "dav"),
            WebDavFixtures.file("/dav/v1.vtt", length = 10, displayName = "v1.vtt"),
            WebDavFixtures.file("/dav/v2.ssa", length = 10, displayName = "v2.ssa"),
            WebDavFixtures.file("/dav/v3.srt", length = 10, displayName = "v3.srt"),
        )

        val found = discovery.discoverSubtitles(videoItem("v1.mp4"))

        assertEquals(setOf("vtt", "ssa", "srt"), found.map { it.extension }.toSet())
        assertTrue(found.all { it.language == null })
        assertEquals("text/vtt", found.first { it.extension == "vtt" }.mimeType)
    }
}
