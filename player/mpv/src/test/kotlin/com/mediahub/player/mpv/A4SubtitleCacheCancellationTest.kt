package com.mediahub.player.mpv

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A4 轮字幕缓存真实取消回归（A4-C5）。
 *
 * 旧实现的 download 用阻塞 [okhttp3.Call.execute] 跑在 withContext(IO) 里：
 * 协程 cancel 无法打断底层 call——等待响应头/读体都停滞到超时，线程被占住。
 * 修复对齐 provider/webdav WebDavCallBridge 模式（本地实现，mpv 模块内）：
 * suspendCancellableCoroutine + invokeOnCancellation { call.cancel() }。
 *
 * 断言三层：
 * 1. 协程取消及时向上传播（本地桥接线程上执行，不再等 socket 超时）；
 * 2. 底层 call 真实取消——EventListener.callFailed 收到 "Canceled" IOException；
 * 3. 桥接线程（mpv-subtitle-bridge）释放：取消后回到池空闲（TIMED_WAITING/消亡），
 *    不再占着 RUNNABLE/BLOCKED/WAITING。
 */
class A4SubtitleCacheCancellationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class RecordingListener : EventListener() {
        val callFailures = mutableListOf<Throwable>()
        val headersDone = CountDownLatch(1)
        override fun callFailed(call: Call, ioe: IOException) {
            callFailures += ioe
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            headersDone.countDown()
        }
    }

    private fun client(listener: RecordingListener) = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS) // 旧实现的"兜底超时"路径（红证据的时长上界）
        .eventListener(listener)
        .build()

    private fun subject(listener: RecordingListener) = SubtitleCache(
        cacheDir = tmp.newFolder(),
        client = client(listener),
        contentResolver = { _, _ -> false },
        logger = StdoutLogger(),
    )

    /** 桥接线程全部空闲（池 poll=TIMED_WAITING）或已消亡才算"释放"。 */
    private fun awaitBridgeReleased(deadlineMs: Long = 10_000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < deadlineMs) {
            val busy = Thread.getAllStackTraces().keys
                .filter { it.name.startsWith("mpv-subtitle-bridge") }
                .filter {
                    it.state == Thread.State.RUNNABLE ||
                        it.state == Thread.State.BLOCKED ||
                        it.state == Thread.State.WAITING
                }
            if (busy.isEmpty()) return
            Thread.sleep(100)
        }
        fail("mpv-subtitle-bridge 线程未释放（取消后仍忙）")
    }

    @Test
    fun `cancel while waiting for headers interrupts call and releases bridge thread`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val listener = RecordingListener()
        val cache = subject(listener)
        val uri = server.url("/dav/movie.vtt").toString()
        val media = server.url("/dav/movie.mkv").toString()

        runBlocking {
            // Dispatchers.IO：job 独立线程推进（runBlocking 单线程会被 takeRequest 阻塞）
            val job = launch(Dispatchers.IO) {
                runCatching { cache.localPathFor(uri, media, "a4-cancel", emptyMap()) }
            }
            assertNotNull("请求必须已到达服务端（execute 阻塞在等响应头）", server.takeRequest(10, TimeUnit.SECONDS))

            val joinedInTime = withTimeoutOrNull(5_000) {
                job.cancelAndJoin()
                true
            }
            assertTrue(
                "取消必须及时传播（远早于 10s readTimeout）——旧实现阻塞 execute 不可中断",
                joinedInTime == true,
            )
        }

        assertTrue(
            "底层 call 必须真实取消（OkHttp 取消形态：Canceled 或 Socket closed）：${listener.callFailures}",
            listener.callFailures.any {
                val m = it.message?.lowercase()
                m?.contains("cancel") == true || m?.contains("socket closed") == true
            },
        )
        awaitBridgeReleased()
    }

    @Test
    fun `cancel during body copy interrupts read and releases bridge thread`() {
        // 64 KiB 体，1 KiB/200ms 节流：完整读 ~13s，取消必须远早于读完
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(ByteArray(64 * 1024) { 0x78 }))
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS),
        )
        val listener = RecordingListener()
        val cache = subject(listener)
        val uri = server.url("/dav/movie.vtt").toString()
        val media = server.url("/dav/movie.mkv").toString()

        runBlocking {
            // Dispatchers.IO：同上，独立线程推进
            val job = launch(Dispatchers.IO) {
                runCatching { cache.localPathFor(uri, media, "a4-cancel", emptyMap()) }
            }
            assertTrue("响应头必须先到达（进入读体阶段）", listener.headersDone.await(10, TimeUnit.SECONDS))

            val joinedInTime = withTimeoutOrNull(5_000) {
                job.cancelAndJoin()
                true
            }
            assertTrue(
                "读体停滞时的取消必须被立即打断——旧实现要等节流体读完",
                joinedInTime == true,
            )
        }
        awaitBridgeReleased()
    }
}
