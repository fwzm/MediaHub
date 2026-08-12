package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.emby.session.EmbySessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-022/026：Phase 1B-2 的 Emby Handle 开放 AUTH + LIBRARY + DETAIL + PLAYBACK，其余能力必须为 null。 */
class EmbyProviderFactoryTest {

    private val server = MediaServer(
        id = "s1", name = "测试", type = ServerType.EMBY,
        baseUrl = "http://localhost:8096", createdAtEpochMs = 0,
    )

    private fun factory(): EmbyProviderFactory {
        // 不依赖 Hilt，直接构造（create 只组装，不触网）
        val logger = StdoutLogger()
        return EmbyProviderFactory(
            httpClientFactory = HttpClientFactory(logger),
            tokenStore = TokenStore(FakeSecretStorage()),
            clientIdentity = ClientIdentity("MediaHub", "Android", "test-device-1", "0.1.0"),
            sessionStoreStorage = FakeSessionStorage(),
            logger = logger,
        )
    }

    @Test
    fun `handle exposes auth library detail playback capabilities`() {
        val handle = factory().create(server)
        assertNotNull(handle.auth)
        assertNotNull(handle.library)
        assertNotNull(handle.detail)
        assertNull(handle.browse)
        assertNotNull(handle.playback)
        assertNull(handle.search)
        assertNull(handle.subtitle)
        assertNull(handle.progress)
        assertEquals(
            setOf(
                ProviderCapability.AUTH,
                ProviderCapability.LIBRARY,
                ProviderCapability.DETAIL,
                ProviderCapability.PLAYBACK,
            ),
            handle.runtimeCapabilities,
        )
        // 运行时 ⊆ 计划
        assertTrue(handle.runtimeCapabilities.all { it in handle.provider.descriptor.declaredCapabilities })
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class FakeSessionStorage : EmbySessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}
