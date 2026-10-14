package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiClient.probe 超时/取消契约（A4：真实 deadline，取代 A3-5 登记的缺陷语义）。
 *
 * A3-5 曾登记的缺陷（timeoutMs 死参数 + 取消打不断阻塞 execute）已在 A4 修复：
 * probe 改为 enqueue 桥（invokeOnCancellation → 真实 Call.cancel）+
 * `withTimeoutOrNull(timeoutMs)` 真实 deadline。本测试按**新契约**锁定：
 *
 * 1. 正常 2xx：即时成功，不回归。
 * 2. timeoutMs 构成真实 deadline：服务端 1200ms 才回，timeoutMs=300 的 probe
 *    在 <1000ms 内返回 Failure（userMessage 含"超时"，detail 对齐
 *    PlaybackError.Code.NETWORK_TIMEOUT），且触发真实 Call.cancel
 *    （EventListener.canceled），不再等到 readTimeout/callTimeout。
 * 3. 外层协程取消同样经 invokeOnCancellation → 真实 Call.cancel 打断：
 *    withTimeoutOrNull(500) 包裹 stalled probe 返回 null 且 elapsed<1500
 *    （旧实现要等 callTimeout≈2s）。
 */
class ApiClientProbeTimeoutTest {

    private lateinit var webServer: MockWebServer
    private val logger = StdoutLogger()
    private val canceledCalls = CountDownLatch(1)

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        try {
            webServer.shutdown()
        } catch (ignored: Exception) {
        }
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                canceledCalls.countDown()
            }
        })
        .build()

    @Test
    fun `probe returns promptly on 2xx`() = runBlocking {
        webServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = ApiClient(client(), logger = logger)
        val started = System.nanoTime()
        val result = client.probe(webServer.url("/probe").toString(), timeoutMs = 5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull(result)
        assertTrue(result is ServerProbeResult.Success)
        assertTrue("正常路径应远小于 timeoutMs（实际 ${elapsedMs}ms）", elapsedMs < 3_000)
    }

    @Test
    fun `probe timeoutMs is a real deadline - stalled server fails fast with timeout`() = runBlocking {
        // 服务端 1200ms 后才返回 200：deadline=300ms 的 probe 不得等服务端
        webServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(1_200)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        val client = ApiClient(client(), logger = logger)
        val started = System.nanoTime()
        val result = client.probe(webServer.url("/probe").toString(), timeoutMs = 300)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("deadline 命中必须返回 Failure，实际 $result", result is ServerProbeResult.Failure)
        val failure = result as ServerProbeResult.Failure
        assertTrue("用户文案必须包含\"超时\"，实际 ${failure.userMessage}", failure.userMessage.contains("超时"))
        assertEquals(
            "detail 对齐 PlaybackErrorMapper 的超时码",
            PlaybackError.Code.NETWORK_TIMEOUT.name,
            failure.detail,
        )
        assertTrue("必须在 deadline 附近返回而非等服务端/OkHttp 超时（实际 ${elapsedMs}ms）", elapsedMs < 1_000)
        assertTrue("deadline 命中必须真实 Call.cancel（EventListener.canceled）", canceledCalls.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `outer cancellation interrupts the probe via real call cancel`() = runBlocking {
        // 服务端一直挡住（barrier），外层 withTimeoutOrNull(500) 取消 probe：
        // 取消必须经 invokeOnCancellation → 真实 Call.cancel 打断，~500ms 返回 null，
        // 不再等 callTimeout≈2s（A3-5 登记的"取消打不断阻塞 execute"缺陷已被本修复取代）
        val release = CountDownLatch(1)
        webServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(6, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        val client = ApiClient(client(), logger = logger)
        val started = System.nanoTime()
        val outcome = withTimeoutOrNull(500) {
            client.probe(webServer.url("/probe").toString(), timeoutMs = 5_000)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull("外层超时必须返回 null（取消透传）", outcome)
        assertTrue(
            "取消必须真实打断 probe（不再等 callTimeout≈2s，实际 ${elapsedMs}ms）",
            elapsedMs < 1_500,
        )
        assertTrue("外层取消必须触发真实 Call.cancel（EventListener.canceled）", canceledCalls.await(2, TimeUnit.SECONDS))
        release.countDown()
    }
}
