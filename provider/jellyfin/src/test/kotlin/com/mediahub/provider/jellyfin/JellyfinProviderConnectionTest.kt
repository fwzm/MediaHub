package com.mediahub.provider.jellyfin

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 协议级连接测试（ADR-019）：Jellyfin SystemInfo 特征校验 + 反代子路径保留（ADR-039）。 */
class JellyfinProviderConnectionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer().apply { start() } }

    @After
    fun tearDown() { server.shutdown() }

    private fun provider(baseUrl: String = server.url("/").toString().trimEnd('/')): JellyfinProvider {
        val logger = StdoutLogger()
        val http = HttpClientFactory(logger)
        val authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(
            ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        )
        val jellyfinApi = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(baseUrl),
            apiClient = ApiClient(http.apiClient(), logger = logger),
            authHeaderBuilder = authHeaderBuilder,
            logger = logger,
        )
        return JellyfinProvider(
            server = MediaServer(
                id = "s1", name = "测试", type = ServerType.JELLYFIN,
                baseUrl = baseUrl, createdAtEpochMs = 0,
            ),
            apiClient = ApiClient(http.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(http.mediaClient(), logger = logger),
            tokenStore = TokenStore(FakeSecretStorage()),
            logger = logger,
            jellyfinApi = jellyfinApi,
            authHeaderBuilder = authHeaderBuilder,
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

    // ---- ADR-039：反代子路径必须保留，禁止 /emby 前缀 ----

    @Test
    fun `reverse proxy subpath is preserved and no emby prefix is added`() = runBlocking {
        // base = https://host/jellyfin → 探针必须打 /jellyfin/System/Info/Public
        val subpathBase = server.url("/jellyfin").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Id":"jf-1","Version":"10.9.0"}""")
        )
        val result = provider(subpathBase).testConnection()
        assertTrue("ok=$result", result.ok)
        val request = server.takeRequest()
        assertEquals("/jellyfin/System/Info/Public", request.requestUrl!!.encodedPath)
        assertFalse(request.requestUrl!!.encodedPath.contains("/emby"))
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }
}
