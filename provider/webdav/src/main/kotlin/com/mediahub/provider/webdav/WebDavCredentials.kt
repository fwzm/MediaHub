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
 * **A3-2 身份契约**：凭据不仅按 serverId 隔离，还绑定**身份指纹**
 * （[identityOf]：用户名 + 规范化地址）。vault 里的密码只服务于同身份的
 * handle——地址 A 被替换为 B 后，旧 A handle 不得读到 B 的密码并发往旧地址。
 *
 * **认证 attempt/条件提交**：[beginAuthenticationAttempt] 捕获世代，
 * [commitAuthentication] 仅在世代未推进时落盘（旧登录的迟到 2xx 不得覆盖
 * 较新身份；logout/删除/restore 失效后的迟到成功不得复活凭据）。
 *
 * 线性化点：每 serverId 一把 [WebDavCredentialCoordinator] Mutex，世代判定/
 * 推进、身份指纹维护与 vault 读/写/删在同一次持锁内完成；锁内零网络等待。
 * 失败序：commit 先增代再写库——写库失败世代已推进（在途旧 attempt 全部
 * 作废，方向安全），vault 保持权威内容，身份指纹仅在写库成功后更新。
 *
 * **边界（如实声明）**：全部为**进程内**协调（单 app 进程内的 Mutex/世代/
 * attempt），不提供跨进程一致性，也不是跨设备同步；本层不承诺任何跨进程
 * 或跨设备的凭据状态同步。与备份恢复身份变更的对齐点：恢复失效器经
 * [WebDavCredentialGenerationInvalidator]（provider:api 集合注入）调用
 * [WebDavCredentialCoordinator.invalidate]，与 TokenStore restore lease 在
 * 同一身份事件上一起推进（进程内语义）。
 */
internal class WebDavCredentialStore(
    private val vault: CredentialVault,
    private val coordinator: WebDavCredentialCoordinator,
) {

    /** 密码值 + 读取时所属的凭据世代。 */
    data class PasswordHandle(val password: String, val generation: Long)

    /** 身份指纹：用户名 + 规范化 base 地址（handle 的 server 快照指纹）。 */
    fun identityOf(server: MediaServer): String =
        server.username?.trim().orEmpty() + "|" + WebDavUrls.normalizeBase(server.baseUrl)

    /** 认证开始时捕获世代（锁内快照）；网络探测在锁外进行。 */
    suspend fun beginAuthenticationAttempt(serverId: String): Long =
        coordinator.withServerLock(serverId) { it.generation }

    /**
     * 认证成功的条件提交：仅当世代仍等于 [attemptGeneration] 才写库并更新身份
     * 指纹。返回 false = 已被较新的身份操作（新登录/登出/删除/restore 失效）
     * 取代，本次结果不得落盘。vault 写失败时异常上抛（世代已推进：本次
     * attempt 作废，方向安全）。
     */
    suspend fun commitAuthentication(
        serverId: String,
        attemptGeneration: Long,
        password: String,
        identity: String,
    ): Boolean = coordinator.withServerLock(serverId) { state ->
        if (state.generation != attemptGeneration) return@withServerLock false
        state.generation++
        vault.save(serverId, CredentialVault.CredentialKind.PASSWORD, password)
        state.credentialIdentity = identity
        true
    }

    /**
     * 供 [WebDavSession] 取授权头：身份指纹不符（地址/用户名已替换）或无密码
     * 时抛 [ProviderException.AuthRequired]——旧 handle 绝不读到新身份的密码，
     * 绝不以匿名降级代替。
     */
    suspend fun authorizationFor(server: MediaServer): String =
        coordinator.withServerLock(server.id) { state ->
            if (state.credentialIdentity != identityOf(server)) {
                throw ProviderException.AuthRequired(server.id)
            }
            val password = vault.read(server.id, CredentialVault.CredentialKind.PASSWORD)
                ?: throw ProviderException.AuthRequired(server.id)
            WebDavAuth.basicHeader(server.username.orEmpty(), password)
        }

    /**
     * 同身份读取密码 + 捕获世代（restoreSession 用）：身份指纹不符时返回
     * null——旧 handle 视为无凭据，**不发出任何网络请求**。
     */
    suspend fun readPasswordFor(server: MediaServer): PasswordHandle? =
        coordinator.withServerLock(server.id) { state ->
            if (state.credentialIdentity != identityOf(server)) return@withServerLock null
            vault.read(server.id, CredentialVault.CredentialKind.PASSWORD)
                ?.let { PasswordHandle(it, state.generation) }
        }

    /** 迟到成功返回前的再校验：世代与身份指纹均未变才允许发布 Authenticated。 */
    suspend fun isStillCurrent(server: MediaServer, handle: PasswordHandle): Boolean =
        coordinator.withServerLock(server.id) { state ->
            state.generation == handle.generation && state.credentialIdentity == identityOf(server)
        }

    /** 无身份判定的原始读取（世代语义测试/内部诊断用；生产路径勿用）。 */
    suspend fun readPassword(serverId: String): PasswordHandle? =
        coordinator.withServerLock(serverId) { state ->
            vault.read(serverId, CredentialVault.CredentialKind.PASSWORD)
                ?.let { PasswordHandle(it, state.generation) }
        }

    /** 只取密码值（同身份约束下的轻量读取）。 */
    suspend fun readPasswordValue(server: MediaServer): String? =
        readPasswordFor(server)?.password

    /**
     * 仅当凭据世代仍与 [handle] 一致才清除，返回是否执行了删除。
     * vault.remove 失败时异常上抛（世代未动，可重试）。
     */
    suspend fun clearIfStill(serverId: String, handle: PasswordHandle): Boolean =
        coordinator.withServerLock(serverId) { state ->
            if (state.generation != handle.generation) return@withServerLock false
            vault.remove(serverId, CredentialVault.CredentialKind.PASSWORD)
            state.credentialIdentity = null
            true
        }

    /** 显式登出/删除：先删后推进世代；失败时世代未动，可重试。 */
    suspend fun clear(serverId: String) {
        coordinator.withServerLock(serverId) { state ->
            vault.remove(serverId, CredentialVault.CredentialKind.PASSWORD)
            state.generation++
            state.credentialIdentity = null
        }
    }

    /**
     * 直写（测试/工具路径）：无条件保存密码并绑定 [server] 的身份指纹。
     * 生产认证路径必须走 [beginAuthenticationAttempt]+[commitAuthentication]。
     */
    suspend fun savePassword(server: MediaServer, password: String) {
        coordinator.withServerLock(server.id) { state ->
            state.generation++
            vault.save(server.id, CredentialVault.CredentialKind.PASSWORD, password)
            state.credentialIdentity = identityOf(server)
        }
    }
}

/**
 * 单次会话所需的地址与鉴权头。
 *
 * 授权头经 [WebDavCredentialStore.authorizationFor] 按**身份指纹**获取：
 * 本 handle 持有的 server 快照（地址+用户名）与 vault 当前凭据身份不符时
 * fail-closed（[ProviderException.AuthRequired]），绝不以新身份的密码发往
 * 旧地址，也绝不构造占位/空凭据或匿名降级。
 */
internal class WebDavSession(
    private val server: MediaServer,
    private val credentials: WebDavCredentialStore,
) {
    /** PROPFIND / 播放请求使用的根目录（保证以 `/` 结尾）。 */
    val baseUrl: String get() = WebDavUrls.normalizeBase(server.baseUrl)

    suspend fun authorization(): String = credentials.authorizationFor(server)
}
