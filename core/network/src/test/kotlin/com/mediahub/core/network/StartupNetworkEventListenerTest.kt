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
 * StartupNetworkEventListener 测试（U4-B）：
 * URL 匹配（PlaybackInfo POST / media GET）、sink 回调时序、无敏感信息泄漏。
 */
class StartupNetworkEventListenerTest {

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
        if (method == "POST") {
            builder.post(okhttp3.RequestBody.create(null, "{}"))
        }
        client().newCall(builder.build()).execute().close()
    }

    @Test
    fun `playbackInfo POST 触发 piStart 和 piEnd`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val url = server.url("/emby/Items/123/PlaybackInfo").toString()
        fire(url, method = "POST")
        assertEquals(listOf("piStart", "piEnd"), events)
    }

    @Test
    fun `media stream GET 触发 mediaStart 和 firstByte`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data"))
        val url = server.url("/emby/Videos/123/stream.mkv").toString()
        fire(url)
        assertEquals(listOf("mediaStart", "firstByte"), events)
    }

    @Test
    fun `普通 API GET 不触发任何回调`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val url = server.url("/emby/Users/1/Items").toString()
        fire(url)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `无 sink 时不崩溃`() {
        PlaybackNetworkTraceRegistry.set(null)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val url = server.url("/emby/Items/1/PlaybackInfo").toString()
        fire(url, method = "POST")
        assertTrue(events.isEmpty())
    }
}