package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No wall-clock races: the scheduler holds resumption until after cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class EndpointTestServiceOwnershipTest {
    @Test
    fun `response queued for resumption is closed when caller is cancelled`() {
        val api = ControlledCall()
        val body = ObservedBody()
        Fixture(listOf(api)).use { fixture ->
            fixture.start()
            assertTrue(api.enqueued)

            // onResponse returns, but the caller cannot resume until runCurrent below.
            val callbackFailure = runCatching { api.deliver(200, body) }.exceptionOrNull()
            assertNull("onResponse must not throw", callbackFailure)
            assertNull(fixture.outcome)
            fixture.job.cancel()
            fixture.dispatcher.scheduler.runCurrent()

            assertTrue("queued response must be closed on prompt cancellation", body.closed)
            assertTrue(api.cancelled)
            assertTrue(fixture.job.isCompleted)
            assertTrue(fixture.outcome?.exceptionOrNull() is CancellationException)
            assertTrue("no unhandled callback/coroutine exception", fixture.unhandled.isEmpty())
        }
    }

    @Test
    fun `media read failure after headers preserves api status and closes response`() {
        val api = ControlledCall()
        val media = ControlledCall()
        val apiBody = ObservedBody()
        val failedBody = ObservedBody(failRead = true)
        Fixture(listOf(api, media)).use { fixture ->
            fixture.start()
            api.deliver(200, apiBody)
            fixture.dispatcher.scheduler.runCurrent()
            assertTrue(media.enqueued)

            assertNull("read failure must not escape callback", runCatching {
                media.deliver(206, failedBody)
            }.exceptionOrNull())
            fixture.dispatcher.scheduler.runCurrent()

            assertTrue(fixture.job.isCompleted)
            val result = fixture.outcome!!.getOrThrow()
            assertTrue("failure occurs inside body read, after media headers", failedBody.readEntered)
            assertTrue(apiBody.closed)
            assertTrue(failedBody.closed)
            assertEquals("failed media consumption must retain API status", 200, result.httpCode)
            assertEquals(0L, result.apiLatencyMs)
            assertEquals(0L, result.mediaFirstByteMs)
            assertTrue("header-based range observation is retained", result.supportsRange)
            assertNull(result.mediaThroughputMbps)
            assertNull(result.error)
            assertFalse(fixture.job.isCancelled)
            assertTrue(fixture.unhandled.isEmpty())
        }
    }

    private class Fixture(calls: List<ControlledCall>) : AutoCloseable {
        val dispatcher = StandardTestDispatcher()
        val unhandled = mutableListOf<Throwable>()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + dispatcher + CoroutineExceptionHandler { _, e ->
            unhandled += e
        })
        private val client = OkHttpClient()
        private val remaining = ArrayDeque(calls)
        private val subject = object : EndpointTestService(
            HttpClientFactory(StdoutLogger()), clock = { 0L }, ioDispatcher = dispatcher,
        ) {
            override fun createApiClient() = client
            override fun createMediaClient() = client
            override fun newCall(client: OkHttpClient, request: Request): Call = remaining.removeFirst()
        }
        var outcome: Result<EndpointTestResult>? = null
        val job = scope.launch {
            outcome = runCatching { subject.test("http://127.0.0.1", "/probe") }
        }

        fun start() = dispatcher.scheduler.runCurrent()

        override fun close() {
            parent.cancel()
            dispatcher.scheduler.runCurrent()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            check(job.isCompleted) { "test coroutine did not terminate" }
        }
    }

    private class ControlledCall : Call {
        private val request = Request.Builder().url("http://127.0.0.1/probe").build()
        private lateinit var callback: Callback
        var enqueued = false
        var cancelled = false
        override fun request() = request
        override fun execute(): Response = error("test requires asynchronous enqueue")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued = true
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = enqueued
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall()
        fun deliver(code: Int, body: ResponseBody) = callback.onResponse(this, Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build())
    }

    private class ObservedBody(private val failRead: Boolean = false) : ResponseBody() {
        var closed = false
        var readEntered = false
        private val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readEntered = true
                if (failRead) throw IOException("body read failed after headers")
                return -1L
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = source
        override fun close() {
            closed = true
            super.close()
        }
    }
}
