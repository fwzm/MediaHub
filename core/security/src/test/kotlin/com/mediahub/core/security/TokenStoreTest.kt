package com.mediahub.core.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内存版 SecretStorage（测试用）。 */
class FakeSecretStorage : SecretStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun contains(key: String): Boolean = map.containsKey(key)
}

class TokenStoreTest {

    private val store = TokenStore(FakeSecretStorage())

    @Test
    fun `token round trip with refresh and expiry`() = runBlocking {
        val tokens = StoredToken(
            accessToken = "access-abc",
            refreshToken = "refresh-xyz",
            expiresAtEpochMs = 1_700_000_000_000L,
        )
        store.saveTokens("srv1", tokens)

        val read = store.readTokens("srv1")!!
        assertEquals("access-abc", read.accessToken)
        assertEquals("refresh-xyz", read.refreshToken)
        assertEquals(1_700_000_000_000L, read.expiresAtEpochMs)
    }

    @Test
    fun `token without refresh and expiry`() = runBlocking {
        store.saveTokens("srv2", StoredToken(accessToken = "only-access"))
        val read = store.readTokens("srv2")!!
        assertEquals("only-access", read.accessToken)
        assertNull(read.refreshToken)
        assertNull(read.expiresAtEpochMs)
    }

    @Test
    fun `corrupted data returns null`() = runBlocking {
        val store2 = TokenStore(object : SecretStorage {
            override suspend fun put(key: String, value: String) {}
            override suspend fun get(key: String): String? = "!!!not-base64!!!|x|y"
            override suspend fun remove(key: String) {}
            override suspend fun contains(key: String): Boolean = false
        })
        assertNull(store2.readTokens("srv3"))
    }

    @Test
    fun `clear removes tokens`() = runBlocking {
        store.saveTokens("srv4", StoredToken(accessToken = "a"))
        store.clear("srv4")
        assertNull(store.readTokens("srv4"))
    }
}
