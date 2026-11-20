package com.mediahub.core.security

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TokenStore 记录编解码的严格性（凭据完整性）。
 *
 * 存储格式 base64(access)|base64(refresh)|expires：
 * - 非 UTF-8 字节必须判为损坏（不得解码成替换字符后当成有效 Token）；
 * - 空凭据不得被当成有效会话；
 * - 字段数 / 过期时间格式不符仍视为损坏。
 */
class TokenStoreCodecTest {

    private fun storeOf(record: String): TokenStore = TokenStore(object : SecretStorage {
        override suspend fun put(key: String, value: String) = Unit
        override suspend fun get(key: String): String? = record
        override suspend fun remove(key: String) = Unit
        override suspend fun contains(key: String): Boolean = true
    })

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun record(access: String, refresh: String = "", expires: String = "-1"): String =
        access + "|" + refresh + "|" + expires

    @Test
    fun `access token bytes that are not utf8 are rejected instead of replaced`() = runBlocking {
        // 0xC3 缺续字节、0x28 不是续字节：非法 UTF-8 序列
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        assertNull(storeOf(record(b64(invalid))).readTokens("srv-invalid-access"))
    }

    @Test
    fun `refresh token bytes that are not utf8 are rejected`() = runBlocking {
        val invalid = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertNull(storeOf(record(b64("access".toByteArray()), b64(invalid))).readTokens("srv-invalid-refresh"))
    }

    @Test
    fun `blank access token is not a usable session`() = runBlocking {
        assertNull(storeOf(record("")).readTokens("srv-blank"))
        assertNull(storeOf(record(b64("   ".toByteArray()))).readTokens("srv-blank-spaces"))
    }

    @Test
    fun `extra field is still rejected`() = runBlocking {
        assertNull(storeOf(record(b64("access".toByteArray())) + "|extra").readTokens("srv-extra"))
    }

    @Test
    fun `non numeric expiry is rejected as corrupt`() = runBlocking {
        assertNull(storeOf(record(b64("access".toByteArray()), expires = "soon")).readTokens("srv-expiry"))
    }

    @Test
    fun `written tokens still round trip`() = runBlocking {
        val store = TokenStore(FakeSecretStorage())
        val tokens = StoredToken(
            accessToken = "access-value",
            refreshToken = "refresh-value",
            expiresAtEpochMs = 1_700_000_000_000L,
        )
        store.saveTokens("srv-ok", tokens)
        val read = store.readTokens("srv-ok")!!
        assertEquals("access-value", read.accessToken)
        assertEquals("refresh-value", read.refreshToken)
        assertEquals(1_700_000_000_000L, read.expiresAtEpochMs)
    }
}
