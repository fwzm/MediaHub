package com.mediahub.app.image

import com.mediahub.app.MediaHubApp
import com.mediahub.core.network.ServerIdResolver
import com.mediahub.core.network.UserAgentPolicy
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * G5 · Coil 图片出网路径的 **429 行为证据**（只取证，不改生产代码）。
 *
 * 已知残余风险（AGENTS.md §7.1 / queue.json G5）：图片路径没有 429 冷却。
 * 图片列表页会**并发**请求大量封面，一旦某台服务器开始 429，缺少冷却就意味着
 * 每张图都继续出网并继续被拒（重试风暴的温床）。
 *
 * 驱动的是**生产 client 本身**（[MediaHubApp.imageHttpClient]，即 Coil ImageLoader
 * 在 newImageLoader() 中使用的同一个 callFactory）。
 *
 * 证据分两部分：
 * 1. 现状（缺陷）：429 之后，同源后续图片请求仍**全部真实出网**（无冷却）；
 * 2. 对照（同时证明测试有效）：具备冷却的 API 链（HttpClientFactory.apiClient）
 *    在同源 429 之后，后续请求**到达不了服务器**（快速失败）。
 *    对照用例缺失会让「没有冷却」的结论无法与「测试根本没生效」区分。
 */
class ImagePath429EvidenceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun productionImageClient(): OkHttpClient = MediaHubApp.imageHttpClient(
        ledger = null,
        headersForUrl = { null },
        userAgentPolicy = UserAgentPolicy(),
        serverIdFor = ServerIdResolver { "srv-g5" },
    )

    private fun MockWebServer.awaitRequest(label: String): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("5s 内没有请求到达 $label —— 测试未走真实出网路径", request)
        return request!!
    }

    private fun OkHttpClient.codeOf(url: String): Int {
        val response = newCall(Request.Builder().url(url).build()).execute()
        return try {
            response.code
        } finally {
            response.close()
        }
    }

    @Test
    fun `production image client keeps hammering the same origin after a 429`() {
        val client = productionImageClient()
        val firstUrl = server.url("/emby/Items/1/Images/Primary").toString()
        val secondUrl = server.url("/emby/Items/2/Images/Primary").toString()
        val thirdUrl = server.url("/emby/Items/3/Images/Primary").toString()

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        assertEquals("首请求就要把 429 原样交回调用方", 429, client.codeOf(firstUrl))
        server.awaitRequest("img-1-429")

        // 429 之后：同源后续两张图仍然真实出网（这正是缺陷本身）
        server.enqueue(MockResponse().setResponseCode(200).setBody("img-2"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("img-3"))
        assertEquals(200, client.codeOf(secondUrl))
        assertEquals(200, client.codeOf(thirdUrl))
        server.awaitRequest("img-2")
        server.awaitRequest("img-3")

        assertEquals(
            "图片路径若具备 429 冷却，429 之后的请求应在连接前失败、不到达服务器；" +
                "实际请求数说明冷却不存在",
            3,
            server.requestCount,
        )
    }

    @Test
    fun `control — the cooldown equipped api chain does fast fail after a 429`() {
        // 对照：同一张冷却表生效的 API 链（生产 HttpClientFactory 派生），
        // 用同一台 MockWebServer，同样解析得到 serverId。
        val cooldown = com.mediahub.core.network.RateLimitCooldown()
        val factory = com.mediahub.core.network.HttpClientFactory(
            logger = object : com.mediahub.core.logging.Logger {
                override fun d(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
                override fun i(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
                override fun w(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
                override fun e(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
            },
            userAgentPolicy = UserAgentPolicy(),
            serverIdFor = ServerIdResolver { "srv-g5" },
            rateLimitCooldown = cooldown,
        )
        val apiClient = factory.apiClient()
        val firstUrl = server.url("/emby/Users/count").toString()
        val secondUrl = server.url("/emby/Items/9/Images/Primary").toString()

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        assertEquals(429, apiClient.codeOf(firstUrl))
        server.awaitRequest("api-1-429")

        // 冷却生效：后续同源请求在**连接前**失败（抛 RateLimitedException / IOException），
        // 服务器不应再收到任何请求。
        val failed = runCatching { apiClient.codeOf(secondUrl) }
        assertTrue("被冷却作用域的后续请求必须快速失败，实际：$failed", failed.isFailure)
        assertTrue("失败原因必须是冷却，而不是别的网络错误：${failed.exceptionOrNull()}",
            generateSequence<Throwable>(failed.exceptionOrNull()) { it.cause }
                .any { it is com.mediahub.core.network.RateLimitedException })
        assertEquals("服务器不得收到第二次请求（零出网）", 1, server.requestCount)
    }
}
