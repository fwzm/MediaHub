package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 跨 origin 重定向凭据隔离回归测试（Phase 1B-2.2，ADR-030）。
 *
 * 复现背景：media3 1.5.1 DefaultHttpDataSource（setAllowCrossProtocolRedirects(true)）
 * 会把 DataSpec 请求头原样发给每一跳 redirect 目标——双 MockWebServer 曾复现
 * `X-Emby-Token: secret-token` 到达重定向后的第三方主机（明文 HTTP）。
 *
 * 修复后的播放链路：HeaderAwareDataSource → OkHttpDataSource（OkHttp 原生跟随
 * redirect）→ [OriginScopedCredentialInterceptor]（network interceptor，每跳生效）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectCredentialIsolationTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var serverC: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        serverC = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
        serverC.shutdown()
    }

    private fun playbackHeaders(): Map<String, String> = mapOf(
        "X-Emby-Token" to "secret-token",
        "X-Emby-Authorization" to "MediaBrowser Client=\"MediaHub\", Device=\"test\", DeviceId=\"d1\", Version=\"1\"",
        "X-Custom-Media-Header" to "keep-me",
    )

    private fun newSource(): DataSource {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(OriginScopedCredentialInterceptor())
            .build()
        return HeaderAwareDataSource(
            OkHttpDataSource.Factory(client).setUserAgent("MediaHub-test").createDataSource(),
            ::playbackHeaders,
        )
    }

    private fun openReadClose(url: String) {
        val source = newSource()
        source.open(DataSpec(Uri.parse(url))).let { }
        val buf = ByteArray(4)
        source.read(buf, 0, buf.size)
        source.close()
    }

    /** takeRequest 带超时：请求未到达时快速失败（null），避免测试悬挂。 */
    private fun MockWebServer.takeRequestOrFail(label: String): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("no request arrived at $label within 5s", request)
        return request!!
    }

    @Test
    fun `cross origin redirect strips emby credentials and keeps safe headers`() {
        serverA.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", serverB.url("/file.mkv").toString()),
        )
        serverB.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        openReadClose(serverA.url("/video").toString())

        val firstHop = serverA.takeRequestOrFail("serverA")
        assertEquals("secret-token", firstHop.getHeader("X-Emby-Token"))
        assertEquals(1, firstHop.headers.values("X-Emby-Authorization").size)

        val secondHop = serverB.takeRequestOrFail("serverB")
        assertNull("X-Emby-Token must not reach cross-origin redirect target", secondHop.getHeader("X-Emby-Token"))
        assertNull("X-Emby-Authorization must not reach cross-origin redirect target", secondHop.getHeader("X-Emby-Authorization"))
        assertNull("Authorization must not reach cross-origin redirect target", secondHop.getHeader("Authorization"))
        assertNull("Cookie must not reach cross-origin redirect target", secondHop.getHeader("Cookie"))
        // 非敏感媒体头继续透传，证明没有过度剥离。
        assertEquals("keep-me", secondHop.getHeader("X-Custom-Media-Header"))
        assertEquals("MediaHub-test", secondHop.getHeader("User-Agent"))
    }

    @Test
    fun `multi hop cross origin redirect strips credentials on every hop`() {
        serverA.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", serverB.url("/hop2").toString()),
        )
        serverB.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", serverC.url("/file.mkv").toString()),
        )
        serverC.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        openReadClose(serverA.url("/video").toString())

        assertEquals("secret-token", serverA.takeRequestOrFail("serverA").getHeader("X-Emby-Token"))
        assertNull(serverB.takeRequestOrFail("serverB").getHeader("X-Emby-Token"))
        val thirdHop = serverC.takeRequestOrFail("serverC")
        assertNull(thirdHop.getHeader("X-Emby-Token"))
        assertNull(thirdHop.getHeader("X-Emby-Authorization"))
    }

    @Test
    fun `same origin redirect keeps emby credentials`() {
        serverA.enqueue(
            MockResponse().setResponseCode(307)
                .setHeader("Location", serverA.url("/stream.mkv").toString()),
        )
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        openReadClose(serverA.url("/video").toString())

        serverA.takeRequestOrFail("serverA") // 首跳
        val secondHop = serverA.takeRequestOrFail("serverA")
        assertEquals("secret-token", secondHop.getHeader("X-Emby-Token"))
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"test\", DeviceId=\"d1\", Version=\"1\"",
            secondHop.getHeader("X-Emby-Authorization"),
        )
    }

    @Test
    fun `direct request without redirect carries credentials`() {
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        openReadClose(serverA.url("/emby/Videos/1/stream.mkv").toString())

        val request = serverA.takeRequestOrFail("serverA")
        assertEquals("secret-token", request.getHeader("X-Emby-Token"))
        assertEquals("keep-me", request.getHeader("X-Custom-Media-Header"))
    }
}
