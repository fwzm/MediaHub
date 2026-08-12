package com.mediahub.provider.jellyfin

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 协议级连接测试（ADR-019）：Jellyfin SystemInfo 特征校验。 */
class JellyfinProviderConnectionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer().apply { start() } }

    @After
    fun tearDown() { server.shutdown() }

    private fun provider(): JellyfinProvider {
        val logger = StdoutLogger()
        val http = HttpClientFactory(logger)
        return JellyfinProvider(
            server = MediaServer(
                id = "s1", name = "测试", type = ServerType.JELLYFIN,
                baseUrl = server.url("/").toString().trimEnd('/'), createdAtEpochMs = 0,
            ),
            apiClient = ApiClient(http.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(http.mediaClient(), logger = logger),
            tokenStore = TokenStore(FakeSecretStorage()),
            logger = logger,
        )
    }

    @Test
    fun `valid jellyfin system info passes`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"jf-1","ServerName":"Jellyfin 服","Version":"10.9.0"}"""
            )
        )
        val result = provider().testConnection()
        assertTrue("ok=$result", result.ok)
        assertTrue(result.message!!.contains("10.9.0"))
    }

    @Test
    fun `http 200 but wrong json fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hello":"world"}"""))
        val result = provider().testConnection()
        assertFalse(result.ok)
        assertTrue(result.message!!.contains("不是有效的 Jellyfin"))
    }

    @Test
    fun `http 404 fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = provider().testConnection()
        assertFalse(result.ok)
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }
}
