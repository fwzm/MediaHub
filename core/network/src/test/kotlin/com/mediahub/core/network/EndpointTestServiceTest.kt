package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Phase 1I 2C：正常路径与分层失败语义回归（锁定未授权变更）----

    @Test
    fun `two layer success reports media status and range support`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody("abcdef")
                .addHeader("Accept-Ranges", "bytes")
        )

        val result = service().test(server.url("/").toString().trimEnd('/'), "/System/Info/Public")

        assertNull("两层都成功时不得给出错误", result.error)
        assertEquals("httpCode 沿用 Media 阶段观测值", 206, result.httpCode)
        assertTrue("206 应判定支持 Range", result.supportsRange)
        assertNotNull("Media 阶段首包耗时必须记录", result.mediaFirstByteMs)

        val api = server.takeRequest()
        val media = server.takeRequest()
        assertEquals("/System/Info/Public", api.requestUrl!!.encodedPath)
        assertEquals("bytes=0-1048575", media.getHeader("Range"))
    }

    @Test
    fun `media failure keeps valid api result`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = service().test(server.url("/").toString().trimEnd('/'), "/System/Info/Public")

        assertNull("Media 失败不得把 API 结果标成错误", result.error)
        assertEquals("API 层状态码必须保留", 200, result.httpCode)
        assertNull("Media 未取到首包", result.mediaFirstByteMs)
        assertFalse(result.supportsRange)
    }

    @Test
    fun `api failure skips media layer and reports error`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = service().test(server.url("/").toString().trimEnd('/'), "/System/Info/Public")

        assertNotNull("API 失败必须给出错误", result.error)
        assertNull("API 失败时不得进入 Media 阶段", result.mediaFirstByteMs)
        // 只允许 apiClient 自身的幂等重试次数，不得再发出 Media 请求
        assertTrue(
            "不得越过 API 阶段发出 Media 请求，实际请求数=${server.requestCount}",
            server.requestCount <= 2,
        )
    }

    @Test
    fun `empty media body yields no throughput and no range claim`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = service().test(server.url("/").toString().trimEnd('/'), "/System/Info/Public")

        assertNull("无响应体不得伪造吞吐", result.mediaThroughputMbps)
        assertFalse(result.supportsRange)
        assertEquals(204, result.httpCode)
    }
}
