package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDAV 取消契约回归（A2-2）：协程取消必须绑定到**真实 Call.cancel**。
 *
 * 断言全部基于可观察事实（OkHttp EventListener 的 canceled / responseFailed /
 * connectionReleased 事件、MockWebServer 请求数、调用方收到的异常类型），
 * 不接受只看 Job.isCancelled。等待为有界条件等待，不靠 sleep 伪造竞态。
 */
class WebDavApiCancellationTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack
    private lateinit var api: WebDavApi

    private val canceledCalls = CountDownLatch(1)
    private val responseFailed = CountDownLatch(1)
    private val connectionReleased = CountDownLatch(1)
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
        // 生产构造路径不变，仅给真实客户端挂 EventListener 观测取消/释放事件
        val observedClient = HttpClientFactory(stack.logger).mediaClient().newBuilder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { canceledCalls.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { responseFailed.countDown() }
                override fun responseFailed(call: Call, ioe: IOException) { responseFailed.countDown() }
                override fun connectionReleased(call: Call, connection: Connection) {
                    connectionReleased.countDown()
                }
            })
            .build()
        api = WebDavApi(WebDavTestStack.SERVER_ID, MediaHttpClient(observedClient, StdoutLogger()), stack.logger)
        runBlocking { stack.storePassword() }
    }

    @After
    fun tearDown() {
        scope?.cancel()
        try {
            webServer.shutdown()
        } catch (ignored: Exception) {
        }
    }

    /** 在服务端确认收到请求后取消（消灭 launch-then-cancel 竞态），返回调用方结果异常。 */
    private fun probeAndCancelAfterArrival(webServer: MockWebServer): Throwable? {
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        var captured: Result<*>? = null
        val completed = CountDownLatch(1)
        val job = s.async {
            captured = runCatching {
                api.propfind(stack.session.baseUrl, depth = 1, authorization = stack.session.authorization())
            }
            completed.countDown()
        }
        assertTrue("探测请求必须已发出", awaitCondition(5_000) { webServer.requestCount > 0 })
        job.cancel()
        assertTrue("调用方协程必须收尾", completed.await(10, TimeUnit.SECONDS))
        return captured!!.exceptionOrNull()
    }

    @Test
    fun `cancel while awaiting headers aborts the real call`() {
        val release = CountDownLatch(1)
        webServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                release.await(8, TimeUnit.SECONDS)
                return okMultistatus()
            }
        }
        val outcome = probeAndCancelAfterArrival(webServer)

        assertTrue("取消必须中止真实 Call（EventListener.canceled）", canceledCalls.await(5, TimeUnit.SECONDS))
        assertTrue("取消必须还原为 CancellationException，实际 $outcome", outcome is CancellationException)
        release.countDown()
    }

    @Test
    fun `cancel during stalled body read aborts read and releases response`() {
        // 头已到、体停滞：声明 Content-Length 但不发送任何字节
        webServer.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setHeader("Content-Length", "65536"),
        )
        val outcome = probeAndCancelAfterArrival(webServer)

        assertTrue("停滞读取必须被 Call.cancel 中止（responseFailed/callFailed）", responseFailed.await(5, TimeUnit.SECONDS))
        assertTrue("连接必须释放", connectionReleased.await(5, TimeUnit.SECONDS))
        assertTrue("读体取消必须以 CancellationException 呈现，实际 $outcome", outcome is CancellationException)
    }

    @Test
    fun `cancellation before request start sends no request`() {
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        val outcome = runBlocking {
            val job = s.async { api.propfind(stack.session.baseUrl, depth = 1, authorization = stack.session.authorization()) }
            job.cancel()
            runCatching { job.await() }.exceptionOrNull()
        }
        assertTrue(outcome is CancellationException)
        assertEquals("开始前取消不得发出任何请求", 0, webServer.requestCount)
    }

    @Test
    fun `transport failure maps to network exception and is distinct from cancellation`() = runBlocking {
        webServer.shutdown()
        val dead = WebDavApi(
            WebDavTestStack.SERVER_ID,
            MediaHttpClient(
                OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
                stack.logger,
            ),
            stack.logger,
        )
        val outcome = runCatching {
            dead.propfind("http://127.0.0.1:1/dav/", depth = 0, authorization = null)
        }.exceptionOrNull()
        assertTrue(
            "普通 IO 失败必须是 Network 异常，实际 $outcome",
            outcome is com.mediahub.provider.api.ProviderException.Network,
        )
    }

    private fun okMultistatus() = okhttp3.mockwebserver.MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml")
        .setBody(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "root")))

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(20)
        }
        return true
    }
}
