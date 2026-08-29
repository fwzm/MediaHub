package com.mediahub.provider.emby.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.ServerType
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Emby 图片鉴权贡献者（ADR-039 下沉回归）：头生成与原 app 层 EmbyImageAuthStore
 * **逐字节等价**——X-Emby-Token + X-Emby-Authorization（含 UserId），未登录 → null。
 */
class EmbyImageAuthContributorTest {

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

    private fun contributor(): Pair<EmbyImageAuthContributor, Pair<FakeSecretStorage, FakeSessionStorage>> {
        val tokenStorage = FakeSecretStorage()
        val sessionStorage = FakeSessionStorage()
        val contributor = EmbyImageAuthContributor(
            tokenStore = TokenStore(tokenStorage),
            sessionStorage = sessionStorage,
            identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0"),
        )
        return contributor to (tokenStorage to sessionStorage)
    }

    @Test
    fun `logged in yields exact emby header pair identical to legacy store behavior`() = runBlocking {
        val (contributor, stores) = contributor()
        runBlocking {
            TokenStore(stores.first).saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
            EmbySessionStore(stores.second).save(
                EmbySession(
                    localServerId = "srv-1", remoteServerId = "remote-1",
                    userId = "user-1", userName = "Alice",
                )
            )
        }
        val headers = contributor.headersFor("srv-1")
        assertEquals(2, headers?.size)
        assertEquals("tok-1", headers!!["X-Emby-Token"])
        assertEquals(
            "Emby UserId=\"user-1\", Client=\"MediaHub\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"0.1.0\"",
            headers["X-Emby-Authorization"],
        )
    }

    @Test
    fun `signed out yields null`() = runBlocking {
        val (contributor, _) = contributor()
        assertNull(contributor.headersFor("srv-1"))
    }

    @Test
    fun `server type is emby`() {
        val (contributor, _) = contributor()
        assertEquals(ServerType.EMBY, contributor.serverType)
    }
}
