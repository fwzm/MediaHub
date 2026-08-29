package com.mediahub.app.image

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
 * 图片鉴权拦截器（1B-2.3 引入；1G-A 泛化为 provider-agnostic）：
 * 命中 origin 注入头、未命中原样放行、URL 永不携带 Token。
 */
class ProviderImageAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun client(matching: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                ProviderImageAuthInterceptor { url ->
                    if (!matching) return@ProviderImageAuthInterceptor null
                    val isEmbyImage = url.encodedPath.startsWith("/emby/Items/")
                    if (isEmbyImage) mapOf(
                        "X-Emby-Token" to "tok-1",
                        "X-Emby-Authorization" to "Emby Client=\"MediaHub\"",
                    ) else {
                        null
                    }
                },
            )
            .build()

    @Test
    fun `injects auth headers for matching emby image url`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        client(matching = true)
            .newCall(Request.Builder().url(server.url("/emby/Items/1/Images/Primary")).build())
            .execute()
            .close()
        val request = server.takeRequest()
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
        assertEquals("Emby Client=\"MediaHub\"", request.getHeader("X-Emby-Authorization"))
    }

    @Test
    fun `passes through when origin is unknown`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        client(matching = false)
            .newCall(Request.Builder().url(server.url("/emby/Items/1/Images/Primary")).build())
            .execute()
            .close()
        val request = server.takeRequest()
        assertNull(request.getHeader("X-Emby-Token"))
        assertNull(request.getHeader("X-Emby-Authorization"))
    }

    @Test
    fun `url never carries token even when headers are injected`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("img"))
        client(matching = true)
            .newCall(Request.Builder().url(server.url("/emby/Items/1/Images/Primary?tag=abc&maxWidth=400")).build())
            .execute()
            .close()
        val request = server.takeRequest()
        assertNull(request.requestUrl?.queryParameter("token"))
        assertNull(request.requestUrl?.queryParameter("api_key"))
    }
}
