package com.mediahub.core.network

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * MpvHttpBridge ADR-030 安全门禁测试：Emby(A) 返 307 -> CDN(B)，B 绝不能收到 Token/Authorization/Cookie。
 */
class MpvHttpBridgeTest {

    private lateinit var emby: MockWebServer
    private lateinit var cdn: MockWebServer
    private lateinit var bridge: MpvHttpBridge

    @Before
    fun setUp() {
        emby = MockWebServer()
        cdn = MockWebServer()
        emby.start()
        cdn.start()
        bridge = MpvHttpBridge(HttpClientFactory(noOpLogger))
    }

    @After
    fun tearDown() {
        bridge.stop()
        emby.shutdown()
        cdn.shutdown()
    }

    @Test
    fun redirectStripsCredentialsFromCdn() {
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("media-body"))
        emby.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", cdn.url("/file").toString())
        )
        val localUrl = bridge.start(
            emby.url("/media").toString(),
            mapOf("X-Emby-Token" to "secret-token", "Authorization" to "Bearer x", "Cookie" to "sid=1"),
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(localUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("media-body", resp.body?.string())
        }
        // A(Emby) 收到凭据
        val aReq = emby.takeRequest()
        assertEquals("secret-token", aReq.getHeader("X-Emby-Token"))
        // B(CDN) 绝不能收到凭据
        val bReq = cdn.takeRequest()
        assertNull(bReq.getHeader("X-Emby-Token"))
        assertNull(bReq.getHeader("Authorization"))
        assertNull(bReq.getHeader("Cookie"))
    }

    @Test
    fun forwardsRangeHeader() {
        emby.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 100-199/1000").setBody("partial"))
        val localUrl = bridge.start(emby.url("/media").toString(), mapOf("X-Emby-Token" to "tok"))
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(localUrl).header("Range", "bytes=100-199").build()).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("partial", resp.body?.string())
        }
        val aReq = emby.takeRequest()
        assertEquals("bytes=100-199", aReq.getHeader("Range"))
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}