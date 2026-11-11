package com.mediahub.app.image

import com.mediahub.app.MediaHubApp
import com.mediahub.core.network.NetworkExchangeLedger
import com.mediahub.core.network.RequestChannel
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
 * M16-G4 · Coil 图片出网路径的**可观测性边界**回归测试（先红后绿）。
 *
 * 背景（AGENTS.md §7.4 / queue.json G4）：图片加载是生产出网路径之一，但它不经过
 * NetworkExchangeRecorder 的逐交换记账。若不显式登记，台账会把"图片真实出网"
 * 表述成"零交换"（V8 §三 禁止的伪造 0）。
 *
 * 驱动的是**生产 client 本身**（`MediaHubApp.imageHttpClient(...)`，即 Coil ImageLoader
 * 在 `newImageLoader()` 里使用的同一个 callFactory）→ 真实发出一次图片 GET。
 */
class ImageObservabilityBoundaryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun productionClient(
        ledger: NetworkExchangeLedger?,
        headersForUrl: (HttpUrl) -> Map<String, String>? = { null },
    ): OkHttpClient = MediaHubApp.imageHttpClient(
        ledger = ledger,
        headersForUrl = headersForUrl,
        userAgentPolicy = UserAgentPolicy(),
        serverIdFor = ServerIdResolver { null },
    )

    private fun MockWebServer.awaitRequest(label: String): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("5s 内没有请求到达 $label —— 测试未走真实出网路径", request)
        return request!!
    }

    private fun OkHttpClient.getAndClose(url: String): Int {
        val response = newCall(Request.Builder().url(url).build()).execute()
        return try {
            assertEquals(200, response.code)
            response.code
        } finally {
            response.close()
        }
    }

    @Test
    fun `production image client marks IMAGE unobservable at the real network boundary`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake-image-bytes"))
        val ledger = NetworkExchangeLedger()
        assertTrue("接线前台账不应声称部分可观测", !ledger.snapshot().partialObservability)

        productionClient(ledger).getAndClose(server.url("/emby/Items/1/Images/Primary").toString())
        server.awaitRequest("image server")

        val snap = ledger.snapshot()
        assertTrue(
            "Coil 出网边界必须登记不可观测，否则未计数会被伪装成 0（actual=" + snap.coverageNote + "）",
            snap.partialObservability,
        )
        assertTrue("必须精确登记 IMAGE 通道", RequestChannel.IMAGE in snap.partialChannels)
    }

    @Test
    fun `image auth headers still reach the network with the ledger wired`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake-image-bytes"))
        val ledger = NetworkExchangeLedger()
        val client = productionClient(ledger) { url ->
            if (url.encodedPath.startsWith("/emby/Items/")) mapOf("X-Emby-Token" to "tok-img") else null
        }

        client.getAndClose(server.url("/emby/Items/2/Images/Primary").toString())

        // 接线不得破坏既有的图片鉴权语义（M16 只加观测，不改行为）。
        assertEquals("tok-img", server.awaitRequest("image server").getHeader("X-Emby-Token"))
        assertTrue(ledger.snapshot().partialObservability)
    }

    @Test
    fun `without a ledger the production image client stays usable`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake-image-bytes"))

        productionClient(null).getAndClose(server.url("/emby/Items/3/Images/Primary").toString())

        assertNotNull(
            "未注入台账时图片 client 必须照常工作（默认路径行为不变）",
            server.awaitRequest("image server"),
        )
    }
}
