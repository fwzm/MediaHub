package com.mediahub.provider.jellyfin.image

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jellyfin 图片鉴权贡献者（ADR-039）：标准 Authorization 单头；未登录 → null。 */
class JellyfinImageAuthContributorTest {

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private fun contributor(token: String?): Pair<JellyfinImageAuthContributor, FakeSecretStorage> {
        val storage = FakeSecretStorage()
        val contributor = JellyfinImageAuthContributor(
            tokenStore = TokenStore(storage),
            identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0"),
        )
        if (token != null) {
            runBlocking { TokenStore(storage).saveTokens("srv-1", StoredToken(accessToken = token)) }
        }
        return contributor to storage
    }

    @Test
    fun `logged in server yields standard authorization header with token`() = runBlocking {
        val (contributor, _) = contributor("tok-1")
        val headers = contributor.headersFor("srv-1")
        assertEquals(1, headers?.size)
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"0.1.0\", Token=\"tok-1\"",
            headers!!["Authorization"],
        )
    }

    @Test
    fun `signed out server yields null and request passes through`() = runBlocking {
        val (contributor, _) = contributor(null)
        assertNull(contributor.headersFor("srv-1"))
    }

    @Test
    fun `server type is jellyfin`() {
        val (contributor, _) = contributor(null)
        assertEquals(ServerType.JELLYFIN, contributor.serverType)
    }

    // ---- auth scope（ADR-039）：Jellyfin scope = base 原样（保留反代子路径，无 /emby 前缀） ----

    @Test
    fun `auth scope is base url verbatim with subpath preserved`() {
        val (contributor, _) = contributor(null)
        assertEquals("https://host/jellyfin", contributor.authScopeUrl("https://host/jellyfin/"))
        assertEquals("https://host", contributor.authScopeUrl("https://host"))
    }

    @Test
    fun `token never leaks into any url-shaped value`() = runBlocking {
        val (contributor, _) = contributor("tok-1")
        val headers = contributor.headersFor("srv-1")!!
        assertTrue(headers.values.none { it.startsWith("http") })
    }
}
