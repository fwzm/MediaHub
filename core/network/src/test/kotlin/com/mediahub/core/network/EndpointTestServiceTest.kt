package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * EndpointTestService（ADR-039）：协议路径由调用方传入（ProviderDescriptor.probePath），
 * 本类零协议知识——同一路径用于 API latency 与 Media Range 两层；反代子路径保留。
 */
class EndpointTestServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer().apply { start() } }

    @After
    fun tearDown() { server.shutdown() }

    private fun service() = EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L })

    @Test
    fun `uses caller supplied probe path for both layers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().test(server.url("/").toString().trimEnd('/'), "/System/Info/Public")

        assertEquals("/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `emby probe path flows through verbatim`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        // Emby descriptor 自述（含 /emby 前缀）由调用方传入；本类只拼接
        service().test(server.url("/").toString().trimEnd('/'), "/emby/System/Info/Public")

        assertEquals("/emby/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `reverse proxy subpath in base url is preserved`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val baseWithSubpath = server.url("/jellyfin").toString().trimEnd('/')
        service().test(baseWithSubpath, "/System/Info/Public")

        assertEquals("/jellyfin/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
    }
}
