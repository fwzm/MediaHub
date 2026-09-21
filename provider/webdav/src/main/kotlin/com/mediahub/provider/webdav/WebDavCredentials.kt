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
 *
 * **迟到失败防误清**：进程内维护每服务器的凭据**世代**——[savePassword] 先增代再写库，
 * [readPassword] 在读库前捕获当前代；401 清理必须凭捕获代调用 [clearIfStill]。
 * 这样在途请求读旧密码期间发生了重新认证（savePassword 增代），其迟到的 401
 * 会因世代不符而跳过清理，**不会清掉较新身份的密码**。
 * （这是进程内守卫；跨进程一致性由 PR #18 的 lease 机制负责，本分支不引入该依赖。）
 */
internal class WebDavCredentialStore(private val vault: CredentialVault) {

    /** 密码值 + 读取时所属的凭据世代。 */
    data class PasswordHandle(val password: String, val generation: Long)

    private val generations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private fun generationOf(serverId: String): java.util.concurrent.atomic.AtomicLong =
        generations.computeIfAbsent(serverId) { java.util.concurrent.atomic.AtomicLong() }

    suspend fun savePassword(serverId: String, password: String) {
        generationOf(serverId).incrementAndGet()
        vault.save(serverId, CredentialVault.CredentialKind.PASSWORD, password)
    }

    /** 读取密码并捕获其世代；401 清理必须凭该代调用 [clearIfStill]。 */
    suspend fun readPassword(serverId: String): PasswordHandle? {
        val generation = generationOf(serverId).get()
        val password = vault.read(serverId, CredentialVault.CredentialKind.PASSWORD) ?: return null
        return PasswordHandle(password, generation)
    }

    /** 只取密码值（播放/详情等不关心世代的调用点）。 */
    suspend fun readPasswordValue(serverId: String): String? =
        readPassword(serverId)?.password

    /** 仅当凭据世代仍与 [handle] 一致才清除；期间发生过重新认证则跳过。 */
    suspend fun clearIfStill(serverId: String, handle: PasswordHandle) {
        if (generationOf(serverId).get() == handle.generation) clear(serverId)
    }

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
        val password = credentials.readPasswordValue(server.id)
            ?: throw ProviderException.AuthRequired(server.id)
        return WebDavAuth.basicHeader(username, password)
    }
}
