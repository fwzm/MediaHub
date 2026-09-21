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
 * **A2-1 线性化契约**：世代状态在 [WebDavCredentialCoordinator]（应用级共享，
 * per-server 单锁）。本类所有操作的"世代判定/推进 + vault 读/写/删"都在同一次
 * 持锁内完成，锁内不做任何网络等待。失败序（如实声明）：
 * - [savePassword] **先增代后写库**：写库失败时世代已领先——方向安全（在途旧
 *   handle 的条件清理被阻止），且不会留下"半新半旧"的密码；读侧只会看到旧值或 null。
 * - [clear]（显式登出）**先删后增代**：删除失败时世代未动，登出可重试。
 * - [clearIfStill] 检查与删除原子：期间发生过 save/clear/invalidate 则直接返回 false。
 *
 * 不宣称跨进程一致性（见协调器 KDoc）。
 */
internal class WebDavCredentialStore(
    private val vault: CredentialVault,
    private val coordinator: WebDavCredentialCoordinator,
) {

    /** 密码值 + 读取时所属的凭据世代。 */
    data class PasswordHandle(val password: String, val generation: Long)

    suspend fun savePassword(serverId: String, password: String) {
        coordinator.withServerLock(serverId) { state ->
            state.generation++
            vault.save(serverId, CredentialVault.CredentialKind.PASSWORD, password)
        }
    }

    /** 读取密码并捕获其世代；401 条件清理必须凭该代调用 [clearIfStill]。 */
    suspend fun readPassword(serverId: String): PasswordHandle? =
        coordinator.withServerLock(serverId) { state ->
            vault.read(serverId, CredentialVault.CredentialKind.PASSWORD)?.let {
                PasswordHandle(it, state.generation)
            }
        }

    /** 只取密码值（播放/详情等不关心世代的调用点）。 */
    suspend fun readPasswordValue(serverId: String): String? =
        readPassword(serverId)?.password

    /**
     * 仅当凭据世代仍与 [handle] 一致才清除，返回是否执行了删除。
     * vault.remove 失败时异常向上抛出（世代未变，调用方可重试）。
     */
    suspend fun clearIfStill(serverId: String, handle: PasswordHandle): Boolean =
        coordinator.withServerLock(serverId) { state ->
            if (state.generation != handle.generation) return@withServerLock false
            vault.remove(serverId, CredentialVault.CredentialKind.PASSWORD)
            true
        }

    suspend fun clear(serverId: String) {
        coordinator.withServerLock(serverId) { state ->
            vault.remove(serverId, CredentialVault.CredentialKind.PASSWORD)
            state.generation++
        }
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
