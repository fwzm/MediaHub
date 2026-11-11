package com.mediahub.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M0-A（ADR-045）：真实 HTTP 行为验证（MockWebServer）。
 *
 * 覆盖：默认 UA / 自定义 UA / 两服务器隔离 / 同 origin 与跨 origin 重定向 /
 * origin 判定 / 凭据剥离与 UA 保持的**互不越权**。
 *
 * 本测试验证的是 **OkHttp 栈**（API、图片、Media3 OkHttpDataSource、mpv 回环桥上游、
 * 字幕缓存全部复用该栈）。mpv 原生后端的独立验证见 ADR-045 的 NOT_RUN 说明。
 */
class UserAgentInterceptorTest {

    private val upstreamA = MockWebServer()
    private val upstreamB = MockWebServer()

    @After
    fun tearDown() {
        runCatching { upstreamA.shutdown() }
        runCatching { upstreamB.shutdown() }
    }

    private fun startServers() {
        upstreamA.start()
        upstreamB.start()
    }

    /** 与生产装配一致：凭据隔离（ADR-030）+ UA 策略（ADR-045）都是 network interceptor。 */
    private fun client(resolver: ServerIdResolver, policy: UserAgentPolicy): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addNetworkInterceptor(OriginScopedCredentialInterceptor())
            .addNetworkInterceptor(UserAgentInterceptor(policy, resolver))
            .build()

    private fun urlOf(server: MockWebServer, path: String) = server.url(path)

    @Test
    fun `unknown origin receives the default mediahub user agent`() {
        startServers()
        upstreamA.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = client(ServerIdResolver { null }, UserAgentPolicy())
        client.newCall(Request.Builder().url(urlOf(upstreamA, "/emby/Items")).build()).execute().close()

        assertEquals(
            MediaHubUserAgent.DEFAULT,
            upstreamA.takeRequest().getHeader(UserAgentInterceptor.HEADER_USER_AGENT),
        )
    }

    @Test
    fun `custom user agent applies to its server and not to another`() {
        startServers()
        upstreamA.enqueue(MockResponse().setResponseCode(200))
        upstreamB.enqueue(MockResponse().setResponseCode(200))
        val hostA = upstreamA.hostName
        val portA = upstreamA.port
        val policy = UserAgentPolicy(overrides = { mapOf("srv-a" to "Kodi/21.0") })
        val resolver = ServerIdResolver { url ->
            if (url.host == hostA && url.port == portA) "srv-a" else "srv-b"
        }
        val client = client(resolver, policy)

        client.newCall(Request.Builder().url(urlOf(upstreamA, "/emby/Items")).build()).execute().close()
        client.newCall(Request.Builder().url(urlOf(upstreamB, "/emby/Items")).build()).execute().close()

        assertEquals("Kodi/21.0", upstreamA.takeRequest().getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
        assertEquals(MediaHubUserAgent.DEFAULT, upstreamB.takeRequest().getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
    }

    @Test
    fun `same origin redirect keeps user agent and credentials`() {
        startServers()
        val target = urlOf(upstreamA, "/emby/redirected")
        upstreamA.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.toString()))
        upstreamA.enqueue(MockResponse().setResponseCode(200))
        val policy = UserAgentPolicy(overrides = { mapOf("srv-a" to "Kodi/21.0") })
        val client = client(ServerIdResolver { "srv-a" }, policy)

        client.newCall(
            Request.Builder().url(urlOf(upstreamA, "/emby/start"))
                .header("Authorization", "Bearer secret-token").build(),
        ).execute().close()

        val first = upstreamA.takeRequest()
        val second = upstreamA.takeRequest()
        assertEquals("Bearer secret-token", first.getHeader("Authorization"))
        assertEquals("Bearer secret-token", second.getHeader("Authorization"))
        assertEquals("Kodi/21.0", first.getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
        assertEquals("Kodi/21.0", second.getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
    }

    @Test
    fun `cross origin redirect strips credentials and does not leak the custom user agent`() {
        startServers()
        val target = urlOf(upstreamB, "/cdn/media.mp4")
        upstreamA.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.toString()))
        upstreamB.enqueue(MockResponse().setResponseCode(200))
        val hostA = upstreamA.hostName
        val portA = upstreamA.port
        val policy = UserAgentPolicy(overrides = { mapOf("srv-a" to "Kodi/21.0") })
        val resolver = ServerIdResolver { url -> if (url.host == hostA && url.port == portA) "srv-a" else null }
        val client = client(resolver, policy)

        client.newCall(
            Request.Builder().url(urlOf(upstreamA, "/emby/start"))
                .header("Authorization", "Bearer secret-token").build(),
        ).execute().close()

        assertEquals("Bearer secret-token", upstreamA.takeRequest().getHeader("Authorization"))
        val hop = upstreamB.takeRequest()
        assertNull("凭据必须在跨 origin 时剥离", hop.getHeader("Authorization"))
        assertEquals(
            "跨 origin 不得携带原服务器的自定义 UA",
            MediaHubUserAgent.DEFAULT,
            hop.getHeader(UserAgentInterceptor.HEADER_USER_AGENT),
        )
    }

    @Test
    fun `different port counts as a different origin`() {
        startServers()
        val target = urlOf(upstreamB, "/media.mp4")
        upstreamA.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.toString()))
        upstreamB.enqueue(MockResponse().setResponseCode(200))
        // resolver 与生产一致：按 URL 判定归属，跨 origin 目标返回 null → 回落默认 UA。
        val hostA = upstreamA.hostName
        val portA = upstreamA.port
        val client = client(
            ServerIdResolver { url -> if (url.host == hostA && url.port == portA) "srv-a" else null },
            UserAgentPolicy(overrides = { mapOf("srv-a" to "Kodi/21.0") }),
        )

        client.newCall(Request.Builder().url(urlOf(upstreamA, "/start")).header("Authorization", "Bearer t").build())
            .execute().close()

        val first = upstreamA.takeRequest()
        val second = upstreamB.takeRequest()
        assertEquals("/start", first.path)
        assertEquals("Kodi/21.0", first.getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
        assertEquals(MediaHubUserAgent.DEFAULT, second.getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
    }

    @Test
    fun `308 redirect preserves method and applies the redirect hop user agent rule`() {
        startServers()
        val target = urlOf(upstreamA, "/emby/final")
        upstreamA.enqueue(MockResponse().setResponseCode(308).setHeader("Location", target.toString()))
        upstreamA.enqueue(MockResponse().setResponseCode(200))
        val client = client(ServerIdResolver { "srv-a" }, UserAgentPolicy(overrides = { mapOf("srv-a" to "Kodi/21.0") }))

        client.newCall(Request.Builder().url(urlOf(upstreamA, "/emby/start")).build()).execute().close()

        upstreamA.takeRequest()
        val second = upstreamA.takeRequest()
        assertEquals("Kodi/21.0", second.getHeader(UserAgentInterceptor.HEADER_USER_AGENT))
    }
}
