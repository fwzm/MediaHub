package com.mediahub.core.network

import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientCancellationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `cancelling get cancels the in-flight okhttp call without retry`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = apiClient()
        val api = ApiClient(client, logger = StdoutLogger())

        val job = launch(Dispatchers.Default) {
            api.get<JsonObject>(server.url("/slow?SearchTerm=private-title").toString())
        }
        assertNotNull("请求必须已到达 MockWebServer", server.takeRequest(2, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(2_000) {
            job.join()
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }

        assertTrue(job.isCancelled)
        assertEquals("取消不得触发 retry interceptor 的第二次请求", 1, server.requestCount)
    }

    @Test
    fun `cancelling during retry backoff prevents a second attempt`() = runBlocking {
        val retryStarted = CountDownLatch(1)
        val retryMayContinue = CountDownLatch(1)
        val attempts = AtomicInteger()
        val logger = object : Logger {
            override fun d(tag: LogTag, message: String) = Unit
            override fun i(tag: LogTag, message: String) = Unit
            override fun w(tag: LogTag, message: String, throwable: Throwable?) {
                if (message.contains("请求失败，重试")) {
                    retryStarted.countDown()
                    retryMayContinue.await(2, TimeUnit.SECONDS)
                }
            }
            override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
        }
        val client = HttpClientFactory(logger).apiClient().newBuilder()
            .addInterceptor {
                attempts.incrementAndGet()
                throw IOException("forced retryable failure")
            }
            .build()
        val api = ApiClient(client, logger = logger)

        val job = launch(Dispatchers.Default) {
            api.get<JsonObject>(server.url("/retry-backoff").toString())
        }
        try {
            assertTrue(
                "请求必须进入 retry backoff",
                retryStarted.await(2, TimeUnit.SECONDS),
            )
            job.cancel()
        } finally {
            retryMayContinue.countDown()
        }
        withTimeout(2_000) {
            job.join()
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }

        assertTrue(job.isCancelled)
        assertEquals("取消后不得进入第二次 chain.proceed", 1, attempts.get())
        assertEquals("测试 interceptor 应在网络前短路", 0, server.requestCount)
    }

    @Test
    fun `cancelling no-content post cancels the in-flight okhttp call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = apiClient()
        val api = ApiClient(client, logger = StdoutLogger())

        val job = launch(Dispatchers.Default) {
            api.postNoContent(server.url("/slow-post").toString())
        }
        assertNotNull("请求必须已到达 MockWebServer", server.takeRequest(2, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(2_000) {
            job.join()
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling probe cancels the in-flight okhttp call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = apiClient()
        val api = ApiClient(client, logger = StdoutLogger())

        val job = launch(Dispatchers.Default) {
            api.probe(server.url("/slow-probe").toString(), timeoutMs = 60_000)
        }
        assertNotNull("请求必须已到达 MockWebServer", server.takeRequest(2, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(2_000) {
            job.join()
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `probe timeout cancels the call and returns deterministic failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = apiClient()
        val api = ApiClient(client, logger = StdoutLogger())

        val resultDeferred = async(Dispatchers.Default) {
            api.probe(server.url("/timed-out-probe").toString(), timeoutMs = 1_000)
        }
        assertNotNull("请求必须在 timeout 前到达 MockWebServer", server.takeRequest(2, TimeUnit.SECONDS))
        val result = withTimeout(3_000) {
            resultDeferred.await()
        }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }

        assertTrue(result is ServerProbeResult.Failure)
        result as ServerProbeResult.Failure
        assertEquals("连接服务器超时", result.userMessage)
        assertEquals(PlaybackError.Code.NETWORK_TIMEOUT.name, result.detail)
        assertEquals(1, server.requestCount)
    }

    private fun apiClient(): OkHttpClient = HttpClientFactory(StdoutLogger()).apiClient()
}
