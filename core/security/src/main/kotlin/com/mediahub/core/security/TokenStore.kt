package com.mediahub.core.security

import android.util.Base64

/** 某数据源服务器的会话 Token（仅内存使用，落库时加密）。 */
data class StoredToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
)

/**
 * 按 serverId 管理 Token 的加密存取。
 * 存储格式（不依赖 JSON 库）：base64(access) | base64(refresh) | expires
 */
class TokenStore(private val storage: SecretStorage) {

    suspend fun saveTokens(serverId: String, tokens: StoredToken) {
        storage.put(keyFor(serverId), encode(tokens))
    }

    suspend fun readTokens(serverId: String): StoredToken? {
        val raw = storage.get(keyFor(serverId)) ?: return null
        return decode(raw)
    }

    suspend fun clear(serverId: String) {
        storage.remove(keyFor(serverId))
    }

    private fun keyFor(serverId: String) = "token:$serverId"

    private fun encode(tokens: StoredToken): String {
        val access = Base64.encodeToString(tokens.accessToken.toByteArray(), Base64.NO_WRAP)
        val refresh = Base64.encodeToString((tokens.refreshToken ?: "").toByteArray(), Base64.NO_WRAP)
        val expires = tokens.expiresAtEpochMs ?: -1L
        return "$access|$refresh|$expires"
    }

    private fun decode(raw: String): StoredToken? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        return try {
            val access = String(Base64.decode(parts[0], Base64.NO_WRAP), Charsets.UTF_8)
            val refreshRaw = Base64.decode(parts[1], Base64.NO_WRAP)
            val refresh = if (refreshRaw.isEmpty()) null else String(refreshRaw, Charsets.UTF_8)
            val expires = parts[2].toLongOrNull()?.takeIf { it > 0 }
            StoredToken(accessToken = access, refreshToken = refresh, expiresAtEpochMs = expires)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
