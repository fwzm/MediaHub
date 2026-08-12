package com.mediahub.provider.emby

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 协议级连接测试（ADR-019）：HTTP 200 + JSON 可解析 ≠ 有效 Emby。
 */
class EmbyProviderConnectionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(): EmbyProvider {
        val logger = StdoutLogger()
        val http = HttpClientFactory(logger)
        val mediaServer = MediaServer(
            id = "s1",
            name = "测试",
            type = ServerType.EMBY,
            baseUrl = server.url("/").toString().trimEnd('/'),
            createdAtEpochMs = 0,
        )
        return EmbyProvider(
            server = mediaServer,
            apiClient = ApiClient(http.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(http.mediaClient(), logger = logger),
            tokenStore = TokenStore(FakeSecretStorage()),
            logger = logger,
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                com.mediahub.core.common.ClientIdentity("MediaHub", "Android", "test-device", "0.1")
            ),
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
        )
    }

    @Test
    fun `valid emby system info passes`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"abc123","ServerName":"我的Emby","Version":"4.8.1.0"}"""
            )
        )
        val result = provider().testConnection()
        assertTrue("ok=$result", result.ok)
        assertTrue(result.message!!.contains("4.8.1.0"))
    }

    @Test
    fun `http 200 but wrong json fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"foo":"bar"}"""))
        val result = provider().testConnection()
        assertFalse(result.ok)
        assertTrue(result.message!!.contains("不是有效的 Emby"))
    }

    @Test
    fun `http 200 but malformed json fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        val result = provider().testConnection()
        assertFalse(result.ok)
    }

    @Test
    fun `http 404 fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = provider().testConnection()
        assertFalse(result.ok)
        assertTrue(result.message!!.contains("404"))
    }

    @Test
    fun `http 401 fails with auth hint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = provider().testConnection()
        assertFalse(result.ok)
        assertTrue(result.message!!.contains("登录"))
    }

    @Test
    fun `http 403 fails with auth hint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = provider().testConnection()
        assertFalse(result.ok)
        assertTrue(result.message!!.contains("登录"))
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }
}
