package com.mediahub.provider.jellyfin

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Integration gate：ProviderFactory 最终 capability audit（ADR-039，1G 全 slice 落地后）。
 * runtime capabilities 必须精确等于 1G 冻结集合——QUERY/IDENTITY_LOOKUP 因 out-of-scope /
 * 协议缺口必须缺席（不得因后续 slice 意外暴露）。
 */
class JellyfinProviderCapabilityAuditTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStorage: FakeSecretStorage
    private lateinit var sessionStorage: FakeSessionStorage

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStorage = FakeSecretStorage()
        sessionStorage = FakeSessionStorage()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun handle() = JellyfinProviderFactory(
        httpClientFactory = HttpClientFactory(StdoutLogger()),
        tokenStore = TokenStore(tokenStorage),
        clientIdentity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0"),
        jellyfinSessionStorage = sessionStorage,
        logger = StdoutLogger(),
    ).create(
        MediaServer(
            id = "srv-1", name = "Jellyfin", type = ServerType.JELLYFIN,
            baseUrl = server.url("/").toString().trimEnd('/'), createdAtEpochMs = 0,
        )
    )

    @Test
    fun `runtime capabilities equal the frozen 1g set`() {
        val handle = handle()
        assertEquals(
            setOf(
                ProviderCapability.AUTH,
                ProviderCapability.LIBRARY,
                ProviderCapability.DETAIL,
                ProviderCapability.SEARCH,
                ProviderCapability.PLAYBACK,
                ProviderCapability.PROGRESS,
            ),
            handle.runtimeCapabilities,
        )
    }

    // ---- ADR-039 冻结缺席项：QUERY out-of-scope；IDENTITY_LOOKUP 协议缺口 DEFER ----

    @Test
    fun `query and identity lookup remain null`() {
        val handle = handle()
        assertNull(handle.query)
        assertNull(handle.identityLookup)
        assertEquals(false, ProviderCapability.QUERY in handle.runtimeCapabilities)
        assertEquals(false, ProviderCapability.IDENTITY_LOOKUP in handle.runtimeCapabilities)
    }

    // ---- capability 消费面：冻结集合全部由非空实现承载（ADR-022 字段非空纪律） ----

    @Test
    fun `frozen capabilities are backed by non null implementations`() {
        val handle = handle()
        org.junit.Assert.assertNotNull(handle.auth)
        org.junit.Assert.assertNotNull(handle.library)
        org.junit.Assert.assertNotNull(handle.detail)
        org.junit.Assert.assertNotNull(handle.search)
        org.junit.Assert.assertNotNull(handle.playback)
        org.junit.Assert.assertNotNull(handle.progress)
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class FakeSessionStorage : JellyfinSessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}
