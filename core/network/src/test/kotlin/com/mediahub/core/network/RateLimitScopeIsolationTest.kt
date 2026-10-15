package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S3 测试 1：限流作用域隔离。
 *
 * 规则：A 服务器 429 的冷却**不得**波及 B 服务器。
 * - 已知 serverId 时按 serverId 隔离；
 * - 未知 serverId 时回退键必须足够精确（origin：scheme+host+port），
 *   否则**同主机不同端口的另一台服务器会被误冷却**。
 */
class RateLimitScopeIsolationTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { serverA.shutdown() }
        runCatching { serverB.shutdown() }
    }

    private fun factory(): HttpClientFactory = HttpClientFactory(StdoutLogger())

    @Test
    fun `a 429 on one origin does not cool down another origin on the same host`() {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        serverB.enqueue(MockResponse().setResponseCode(200))

        val client = factory().apiClient()

        // A 触发冷却（两台 MockWebServer 的 host 同为 127.0.0.1，仅端口不同）
        val first = client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute()
        assertEquals(429, first.code)
        first.close()
        assertEquals(1, serverA.requestCount)

        // B 必须照常出网：不得因为"同 host"被误冷却
        val second = client.newCall(Request.Builder().url(serverB.url("/Items")).build()).execute()
        assertEquals("同 host 不同端口的另一台服务器不得被冷却", 200, second.code)
        assertEquals("B 必须真实出网一次", 1, serverB.requestCount)
        second.close()

        // A 仍在冷却：不得再次出网
        val blocked = runCatching {
            client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute()
        }
        assertTrue("A 在冷却窗口内必须以可识别错误快速失败", blocked.isFailure)
        assertEquals("冷却窗口内 A 不得再次出网", 1, serverA.requestCount)
    }

    @Test
    fun `a 429 on one path does not cool down a different server id on the same origin`() {
        // 同一 origin（host+port）上两台"服务器"由 serverIdFor 区分
        var currentServerId = "srv-a"
        val factory = HttpClientFactory(
            logger = StdoutLogger(),
            serverIdFor = ServerIdResolver { currentServerId },
        )
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        serverA.enqueue(MockResponse().setResponseCode(200))

        val client = factory.apiClient()
        client.newCall(Request.Builder().url(serverA.url("/srv-a/Items")).build()).execute().close()
        assertEquals(1, serverA.requestCount)

        // 切到另一台服务器（同 origin，不同 serverId）：必须放行
        currentServerId = "srv-b"
        val second = client.newCall(Request.Builder().url(serverA.url("/srv-b/Items")).build()).execute()
        assertEquals("不同 serverId 不得被上一个服务器的冷却误伤", 200, second.code)
        assertEquals(2, serverA.requestCount)
        second.close()
    }
}
