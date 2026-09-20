package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private fun service(): EndpointTestService {
        val tick = AtomicLong()
        return EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { tick.addAndGet(1_000L) })
    }

    @Test
    fun `invalid okhttp host reports api error without preparing media`() = runBlocking {
        var mediaPrepared = false
        var callsCreated = 0
        val subject = object : EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L }) {
            override fun createMediaClient(): OkHttpClient {
                mediaPrepared = true
                return super.createMediaClient()
            }

            override fun newCall(client: OkHttpClient, request: Request): Call {
                callsCreated++
                return super.newCall(client, request)
            }
        }

        // The editor's current normalizer accepts this host, but OkHttp rejects it.
        val result = subject.test("http://%20", "/emby/System/Info/Public")

        assertTrue(result.error!!.startsWith("API test failed: Invalid URL host:"))
        assertEquals(-1L, result.apiLatencyMs)
        assertEquals(0, result.httpCode)
        assertNull(result.protocol)
        assertNull(result.mediaFirstByteMs)
        assertNull(result.mediaThroughputMbps)
        assertFalse(result.supportsRange)
        assertFalse("API preparation failure must skip Media preparation", mediaPrepared)
        assertEquals("Invalid URL must not create any Call", 0, callsCreated)
    }

    @Test
    fun `api call preparation failure reports error and skips media`() = runBlocking {
        var mediaPrepared = false
        val subject = object : EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L }) {
            override fun createMediaClient(): OkHttpClient {
                mediaPrepared = true
                return super.createMediaClient()
            }

            override fun newCall(client: OkHttpClient, request: Request): Call =
                throw IllegalArgumentException("API call setup failed")
        }

        val result = subject.test(server.url("/").toString(), "/probe")

        assertEquals("API test failed: API call setup failed", result.error)
        assertEquals(-1L, result.apiLatencyMs)
        assertEquals(0, result.httpCode)
        assertNull(result.mediaFirstByteMs)
        assertNull(result.mediaThroughputMbps)
        assertFalse(mediaPrepared)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `media call preparation failure preserves api result`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val tick = AtomicLong()
        var mediaPrepared = false
        val subject = object : EndpointTestService(
            HttpClientFactory(StdoutLogger()), clock = { tick.addAndGet(1_000L) },
        ) {
            override fun newCall(client: OkHttpClient, request: Request): Call {
                if (request.header("Range") != null) {
                    mediaPrepared = true
                    throw IllegalArgumentException("Media call setup failed")
                }
                return super.newCall(client, request)
            }
        }

        val result = subject.test(server.url("/").toString(), "/probe")

        assertTrue(mediaPrepared)
        assertNull(result.error)
        assertEquals(1_000L, result.apiLatencyMs)
        assertEquals(202, result.httpCode)
        assertEquals("http/1.1", result.protocol)
        assertNull(result.mediaFirstByteMs)
        assertNull(result.mediaThroughputMbps)
        assertFalse(result.supportsRange)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancellation during call preparation propagates from either layer`() = runBlocking {
        for (cancelMedia in listOf(false, true)) {
            if (cancelMedia) server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val cancellation = CancellationException("cancel during preparation")
            val subject = object : EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L }) {
                override fun newCall(client: OkHttpClient, request: Request): Call {
                    if ((request.header("Range") != null) == cancelMedia) throw cancellation
                    return super.newCall(client, request)
                }
            }

            val failure = runCatching { subject.test(server.url("/").toString(), "/probe") }.exceptionOrNull()

            assertTrue("Preparation cancellation must not become a result", failure is CancellationException)
            assertEquals(cancellation.message, failure?.message)
        }
        assertEquals("Only the Media cancellation case sends an API request", 1, server.requestCount)
    }

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
    fun `accept ranges bytes with status 200 independently reports range support`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("abcdef").addHeader("Accept-Ranges", "bytes"))

        val result = service().test(server.url("/").toString(), "/probe")

        assertNull(result.error)
        assertEquals(200, result.httpCode)
        assertTrue("Accept-Ranges alone must establish range support", result.supportsRange)
    }

    @Test
    fun `short media body throughput uses actual bytes and elapsed time`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(256 * 1024)))

        val result = service().test(server.url("/").toString(), "/probe")

        assertEquals(1_000L, result.apiLatencyMs)
        assertEquals(1_000L, result.mediaFirstByteMs)
        assertEquals("256 KiB over two seconds", 0.125, result.mediaThroughputMbps!!, 0.0)
        assertNull(result.error)
    }

    @Test
    fun `http error statuses in either layer remain observations`() = runBlocking {
        for ((apiCode, mediaCode) in listOf(401 to 200, 503 to 200, 200 to 401, 200 to 503)) {
            server.enqueue(MockResponse().setResponseCode(apiCode).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(mediaCode).setBody("abcdef"))

            val result = service().test(server.url("/").toString(), "/probe")

            assertNull("HTTP $apiCode/$mediaCode is an observation, not a transport failure", result.error)
            assertEquals(mediaCode, result.httpCode)
            assertEquals(1_000L, result.apiLatencyMs)
            assertEquals(1_000L, result.mediaFirstByteMs)
            assertEquals("http/1.1", result.protocol)
            assertEquals(6.0 / (1024 * 1024) / 2, result.mediaThroughputMbps!!, 0.0)
            assertFalse(result.supportsRange)
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals("bytes=0-1048575", server.takeRequest().getHeader("Range"))
        }
        assertEquals(8, server.requestCount)
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

        assertEquals(1_000L, result.apiLatencyMs)
        assertEquals(1_000L, result.mediaFirstByteMs)
        assertNull("无响应体不得伪造吞吐", result.mediaThroughputMbps)
        assertFalse(result.supportsRange)
        assertEquals(204, result.httpCode)
    }
}
