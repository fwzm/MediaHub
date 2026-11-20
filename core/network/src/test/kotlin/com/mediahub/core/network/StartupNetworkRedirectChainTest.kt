package com.mediahub.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * StartupNetworkEventListener 的重定向链行为（U4-B 补充）。
 *
 * OkHttp 对每一次 hop 都会回调 responseHeadersEnd / responseBodyStart，且回调时
 * [okhttp3.Response.priorResponse] 恒为 null（实测），因此监听器必须自己累计 hop 数，
 * 只在**最后一个**响应上报媒体元数据与首字节。否则：
 * - 302 的响应体被当成"媒体首字节"，MEDIA_FIRST_BYTE 被提前盖戳 → TTFF 偏小；
 * - 媒体元数据被 302 的 code/Accept-Ranges/Content-Length 污染；
 * - redirects 恒为 0，重定向链路在 trace 里完全不可见。
 */
class StartupNetworkRedirectChainTest {

    private lateinit var server: MockWebServer
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        PlaybackNetworkTraceRegistry.set(object : PlaybackNetworkTraceSink {
            override fun onPlaybackInfoStart() { events += "piStart" }
            override fun onPlaybackInfoEnd() { events += "piEnd" }
            override fun onMediaRequestStart() { events += "mediaStart" }
            override fun onMediaFirstByte() { events += "firstByte" }
            override fun onMediaResponseMetadata(
                code: Int,
                protocol: String,
                redirectCount: Int,
                acceptsRanges: Boolean,
                contentLengthBytes: Long,
                contentRange: String?,
            ) {
                events += "meta:code=" + code + " redirects=" + redirectCount +
                    " ranges=" + acceptsRanges + " len=" + contentLengthBytes +
                    " range=" + contentRange
            }
        })
    }

    @After
    fun tearDown() {
        PlaybackNetworkTraceRegistry.set(null)
        server.shutdown()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .eventListenerFactory(StartupNetworkEventListener.FACTORY)
        .build()

    private fun fire(url: String, method: String = "GET") {
        val builder = Request.Builder().url(url)
        if (method == "POST") builder.post(okhttp3.RequestBody.create(null, "{}"))
        client().newCall(builder.build()).execute().close()
    }

    private fun redirectTo(path: String): MockResponse = MockResponse()
        .setResponseCode(302)
        .setHeader("Location", server.url(path).toString())
        .setBody("redirect-body")

    private fun mediaMeta(): List<String> = events.filter { it.startsWith("meta:") }

    @Test
    fun `a redirect hop is not reported as the media response`() {
        server.enqueue(redirectTo("/emby/Videos/1/final.mkv"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Accept-Ranges", "bytes").setBody("data"))
        fire(server.url("/emby/Videos/1/stream.mkv").toString())

        assertEquals(listOf("meta:code=200 redirects=1 ranges=true len=4 range=null"), mediaMeta())
    }

    @Test
    fun `every redirect hop is counted`() {
        server.enqueue(redirectTo("/emby/hop2"))
        server.enqueue(redirectTo("/emby/Videos/1/final.mkv"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("data"))
        fire(server.url("/emby/Videos/1/stream.mkv").toString())

        assertEquals(1, mediaMeta().size)
        assertTrue(mediaMeta().single(), mediaMeta().single().contains("redirects=2"))
    }

    @Test
    fun `a direct response still reports zero redirects`() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes 0-1023/8192")
                .setBody("data")
        )
        fire(server.url("/emby/Videos/1/stream.mkv").toString())

        assertEquals(
            listOf("meta:code=206 redirects=0 ranges=true len=4 range=bytes 0-1023/8192"),
            mediaMeta()
        )
    }

    @Test
    fun `first byte is only reported for the final response body`() {
        server.enqueue(redirectTo("/emby/Videos/1/final.mkv"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("data"))
        fire(server.url("/emby/Videos/1/stream.mkv").toString())

        assertEquals(1, events.count { it == "firstByte" })
        assertTrue(
            "firstByte must come after the final response metadata: " + events,
            events.indexOf("firstByte") > events.indexOfFirst { it.startsWith("meta:") }
        )
    }

    @Test
    fun `playback info redirect reports its end only once`() {
        server.enqueue(redirectTo("/emby/Items/1/PlaybackInfoFinal"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        fire(server.url("/emby/Items/1/PlaybackInfo").toString(), method = "POST")

        assertEquals(1, events.count { it == "piStart" })
        assertEquals(1, events.count { it == "piEnd" })
    }

    @Test
    fun `a redirect chain ending in an error reports the final status once`() {
        server.enqueue(redirectTo("/emby/Videos/1/gone.mkv"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        fire(server.url("/emby/Videos/1/stream.mkv").toString())

        assertEquals(1, mediaMeta().size)
        assertTrue(mediaMeta().single(), mediaMeta().single().contains("code=404"))
        assertTrue(mediaMeta().single(), mediaMeta().single().contains("redirects=1"))
    }
}
