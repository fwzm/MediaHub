package com.mediahub.provider.webdav

import com.mediahub.core.security.CredentialVault
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderException

/**
 * Basic 凭据构造。
 *
 * 红线：
 * - 只生成 `Authorization` 头；**绝不**把 `user:pass` 写进 URL
 *   （`http://user:pass@host` 形式既会进日志也可能被重定向带出）。
 * - 输出只存在于内存与请求头；日志侧由 `Logger`/`Redactor` 负责脱敏。
 */
internal object WebDavAuth {

    fun basicHeader(username: String, password: String): String {
        val raw = "$username:$password"
        val encoded = java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }
}

/**
 * WebDAV 长期凭据存取（ADR-016）：密码进 [CredentialVault]（Keystore 加密），
 * 用户名复用 [MediaServer.username]（非敏感身份，已在 Room 中）。
 *
 * 明确不保存凭据到 Room / DataStore 明文；登出时连同 [com.mediahub.core.security.TokenStore] 一起清理。
 */
internal class WebDavCredentialStore(private val vault: CredentialVault) {

    suspend fun savePassword(serverId: String, password: String) {
        vault.save(serverId, CredentialVault.CredentialKind.PASSWORD, password)
    }

    suspend fun readPassword(serverId: String): String? =
        vault.read(serverId, CredentialVault.CredentialKind.PASSWORD)

    suspend fun clear(serverId: String) {
        vault.remove(serverId, CredentialVault.CredentialKind.PASSWORD)
    }
}

/**
 * 单次会话所需的地址与鉴权头。
 *
 * [authorization] 在凭据缺失时抛 [ProviderException.AuthRequired]（fail-closed）：
 * 绝不以匿名请求代替已失效会话，也绝不构造占位/空凭据。
 */
internal class WebDavSession(
    private val server: MediaServer,
    private val credentials: WebDavCredentialStore,
) {
    /** PROPFIND / 播放请求使用的根目录（保证以 `/` 结尾）。 */
    val baseUrl: String get() = WebDavUrls.normalizeBase(server.baseUrl)

    suspend fun authorization(): String {
        val username = server.username?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.AuthRequired(server.id)
        val password = credentials.readPassword(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return WebDavAuth.basicHeader(username, password)
    }
}
