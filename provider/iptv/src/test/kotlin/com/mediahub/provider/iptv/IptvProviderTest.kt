package com.mediahub.provider.iptv

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class IptvProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var provider: IptvProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = dispatcher()
        server.start()
        client = OkHttpClient.Builder().build()
        provider = providerFor(server.url("/playlist.m3u").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `testConnection reports channel count`() = runTest {
        val status = provider.testConnection()

        assertTrue(status.message.orEmpty(), status.ok)
        assertTrue(status.message.orEmpty().contains("4 个频道"))
    }

    @Test
    fun `testConnection rejects non m3u body`() = runTest {
        val status = providerFor(server.url("/bad.m3u").toString()).testConnection()

        assertFalse(status.ok)
        assertEquals("不是有效的 M3U 播放列表", status.message)
    }

    @Test
    fun `testConnection maps missing playlist to http message`() = runTest {
        val status = providerFor(server.url("/missing.m3u").toString()).testConnection()

        assertFalse(status.ok)
        assertTrue(status.message.orEmpty(), status.message.orEmpty().contains("404"))
    }

    @Test
    fun `libraries group channels and paging reports next offset`() = runTest {
        val libraries = provider.getLibraries()

        assertEquals(listOf("央视", "卫视"), libraries.map { it.name })
        assertEquals(listOf(2, 2), libraries.map { it.itemCount })
        assertEquals("LIVE_TV", libraries.first().type.name)

        val firstPage = provider.getItems("央视", PageRequest(offset = 0, limit = 1))
        assertEquals(1, firstPage.items.size)
        assertEquals("CCTV-1", firstPage.items.single().title)
        assertEquals(2, firstPage.totalCount)
        assertTrue(firstPage.hasMore)
        assertEquals(1, firstPage.nextOffset)

        val secondPage = provider.getItems("央视", PageRequest(offset = 1, limit = 1))
        assertEquals("CCTV-2", secondPage.items.single().title)
        assertFalse(secondPage.hasMore)
        assertNull(secondPage.nextOffset)
    }

    @Test
    fun `search filters channels and reuses cached playlist`() = runTest {
        provider.getLibraries()
        val result = provider.search("cctv", PageRequest())

        assertEquals(listOf("CCTV-1", "CCTV-2"), result.items.map { it.title })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty search query does not hit network`() = runTest {
        val result = provider.search("   ", PageRequest())

        assertTrue(result.items.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `resolvePlayback returns direct live source without resume`() = runTest {
        val item = provider.search("湖南", PageRequest()).items.single()

        val source = provider.resolvePlayback(item, PlaybackOptions(startPositionMs = 30_000))

        assertEquals("http://streams.test/hunan.m3u8", source.url)
        assertEquals("application/x-mpegURL", source.mimeType)
        assertEquals("m3u8", source.container)
        assertEquals(PlaybackMode.DIRECT_PLAY, source.mode)
        assertFalse(source.supportsSeeking)
        assertNull(source.durationMs)
        assertNull(source.sessionId)
    }

    @Test
    fun `resolvePlayback rejects non live items`() = runTest {
        val movie = MediaItem(
            serverId = "srv-iptv",
            id = "cctv1",
            type = MediaType.MOVIE,
            title = "movie",
        )

        try {
            provider.resolvePlayback(movie, PlaybackOptions())
            fail("expected NotYetImplemented")
        } catch (expected: ProviderException.NotYetImplemented) {
            assertTrue(expected.message.orEmpty().contains("直播"))
        }
    }

    @Test
    fun `getPrograms filters by channel and time window`() = runTest {
        val programs = provider.getPrograms(
            channelIds = setOf("cctv1"),
            fromEpochMs = Instant.parse("2026-11-14T11:30:00Z").toEpochMilli(),
            toEpochMs = Instant.parse("2026-11-14T12:30:00Z").toEpochMilli(),
        )

        assertEquals(listOf("新闻联播", "焦点访谈"), programs.map { it.title })
        assertTrue(programs.all { it.channelId == "cctv1" })
        assertEquals(
            Instant.parse("2026-11-14T11:00:00Z").toEpochMilli(),
            programs.first().startEpochMs,
        )
    }

    @Test
    fun `getPrograms maps epg channel ids back to provider ids`() = runTest {
        val programs = provider.getPrograms(
            channelIds = setOf("hunan"),
            fromEpochMs = 0,
            toEpochMs = Long.MAX_VALUE,
        )

        assertEquals(listOf("湖南卫视晚间剧场"), programs.map { it.title })
        assertEquals("hunan", programs.single().channelId)
    }

    @Test
    fun `getPrograms without channel ids or epg declaration makes no request`() = runTest {
        val empty = provider.getPrograms(emptySet(), 0, Long.MAX_VALUE)
        assertTrue(empty.isEmpty())
        assertEquals(0, server.requestCount)

        val noEpg = providerFor(server.url("/playlist-no-epg.m3u").toString())
        val result = noEpg.getPrograms(setOf("cctv1"), 0, Long.MAX_VALUE)
        assertTrue(result.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `playlist is cached within ttl`() = runTest {
        provider.getLibraries()
        provider.getLibraries()
        provider.search("cctv", PageRequest())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `handle exposes only implemented capabilities`() {
        val handle = ProviderHandle(
            provider = provider,
            library = provider,
            playback = provider,
            search = provider,
            epg = provider,
        )

        assertEquals(
            setOf(
                ProviderCapability.LIBRARY,
                ProviderCapability.PLAYBACK,
                ProviderCapability.SEARCH,
                ProviderCapability.EPG,
            ),
            handle.runtimeCapabilities,
        )
        assertTrue(handle.hasAnyCapability)
        assertEquals("iptv", handle.provider.descriptor.id)
    }

    private fun providerFor(url: String): IptvProvider {
        val repository = IptvPlaylistRepository(serverId = SERVER_ID, client = client)
        val mediaServer = MediaServer(
            id = SERVER_ID,
            name = "IPTV",
            type = ServerType.IPTV,
            baseUrl = url,
            createdAtEpochMs = 0,
        )
        return IptvProvider(server = mediaServer, repository = repository)
    }

    private fun dispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.path?.substringBefore('?')) {
                "/playlist.m3u" -> ok(playlist(withEpg = true))
                "/playlist-no-epg.m3u" -> ok(playlist(withEpg = false))
                "/epg.xml" -> ok(EPG)
                "/bad.m3u" -> ok("this is not a playlist")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun ok(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/plain; charset=utf-8")
        .setBody(body)

    private fun playlist(withEpg: Boolean): String {
        val header = if (withEpg) {
            "#EXTM3U url-tvg=\"${server.url("/epg.xml")}\""
        } else {
            "#EXTM3U"
        }
        return """
            $header
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
            http://streams.test/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv2" group-title="央视",CCTV-2
            http://streams.test/cctv2.ts
            #EXTINF:-1 tvg-id="hunan" group-title="卫视",湖南卫视
            http://streams.test/hunan.m3u8
            #EXTINF:-1 group-title="卫视",无 tvg-id 频道
            http://streams.test/local.m3u8
        """.trimIndent()
    }

    private companion object {
        const val SERVER_ID = "srv-iptv"

        val EPG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
              <channel id="cctv2"><display-name>CCTV-2</display-name></channel>
              <channel id="hunan"><display-name>湖南卫视</display-name></channel>
              <programme start="20261114190000 +0800" stop="20261114200000 +0800" channel="cctv1">
                <title>新闻联播</title>
              </programme>
              <programme start="20261114200000 +0800" stop="20261114210000 +0800" channel="cctv1">
                <title>焦点访谈</title>
              </programme>
              <programme start="20261114193000 +0800" stop="20261114203000 +0800" channel="hunan">
                <title>湖南卫视晚间剧场</title>
              </programme>
              <programme start="20261114190000 +0800" stop="20261114200000 +0800" channel="unrelated">
                <title>其他频道</title>
              </programme>
            </tv>
        """.trimIndent()
    }
}
