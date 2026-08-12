package com.mediahub.core.security

/**
 * 长期凭据保险库（ADR-016）：加密存储"原始/长期凭据"。
 *
 * 与 [TokenStore]（会话令牌）分工：
 * - TokenStore：access/refresh token（认证后生成的会话）。
 * - CredentialVault：密码、API Key、OAuth client secret、Cookie 会话等长期凭据。
 *
 * 策略约定：
 * - Emby/Jellyfin：登录后仅存 Token，不保存密码。
 * - WebDAV/SMB：长期访问依赖密码 → 经本保险库加密保存。
 * - 云盘：OAuth refresh credential / Cookie → 经本保险库保存。
 * - 禁止 Room/DataStore 明文密码；禁止日志输出。
 */
class CredentialVault(private val storage: SecretStorage) {

    enum class CredentialKind {
        PASSWORD,
        API_KEY,
        REFRESH_TOKEN,
        COOKIE,
        CLIENT_SECRET,
    }

    suspend fun save(serverId: String, kind: CredentialKind, value: String) {
        storage.put(keyFor(serverId, kind), value)
    }

    suspend fun read(serverId: String, kind: CredentialKind): String? =
        storage.get(keyFor(serverId, kind))

    suspend fun contains(serverId: String, kind: CredentialKind): Boolean =
        storage.contains(keyFor(serverId, kind))

    suspend fun remove(serverId: String, kind: CredentialKind) {
        storage.remove(keyFor(serverId, kind))
    }

    /** 清除某服务器的全部凭据。 */
    suspend fun clear(serverId: String) {
        CredentialKind.entries.forEach { kind -> storage.remove(keyFor(serverId, kind)) }
    }

    private fun keyFor(serverId: String, kind: CredentialKind) = "cred:$serverId:${kind.name}"
}
