package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiClient.probe 超时语义锁定（A3-5，独立于已修复的 SLOW-FINAL 退出路径）。
 *
 * 锁定的**当前语义（登记缺陷，不冒充已修复）**：`probe(timeoutMs)` 的
 * timeoutMs 参数不产生真实 deadline——底层是阻塞 `execute()`，
 * 慢响应下 probe 会阻塞到 OkHttp 的 readTimeout/callTimeout，
 * 与传入的 timeoutMs 无关。调用方若要限时必须自己 `withTimeoutOrNull`
 * 包裹（且取消不会中断底层阻塞读——本轮未改动，证据用真实时钟锚定）。
 */
class ApiClientProbeTimeoutTest {

    private lateinit var webServer: MockWebServer
    private val logger = StdoutLogger()

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Test
    fun `probe returns promptly on 2xx`() = runBlocking {
        webServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = ApiClient(client(), logger = logger)
        val started = System.nanoTime()
        val result = client.probe(webServer.url("/probe").toString(), timeoutMs = 5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull(result)
        assertTrue("正常路径应远小于 timeoutMs（实际 ${elapsedMs}ms）", elapsedMs < 3_000)
    }

    @Test
    fun `probe timeoutMs does not bound a stalled response - documented limitation`() = runBlocking {
        // 服务端挡住响应（不返回）。锁定的缺陷语义（双层）：
        // 1) probe 自身 timeoutMs=5000 不构成 deadline；
        // 2) 即便调用方 withTimeoutOrNull(500) 包裹，取消也打不断阻塞 execute——
        //    withTimeoutOrNull 要等底层调用结束（≈callTimeout）才返回 null。
        // 这正是 SLOW-FINAL 在 execute 路径已修、probe 路径未修的同型缺陷。
        val release = CountDownLatch(1)
        webServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
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

        assertNull("最终返回 null（超时路径）", outcome)
        assertTrue(
            "取消打不断阻塞 execute：必须等到 callTimeout≈2s（实际 ${elapsedMs}ms）",
            elapsedMs >= 1_500,
        )
        release.countDown()
    }
}
