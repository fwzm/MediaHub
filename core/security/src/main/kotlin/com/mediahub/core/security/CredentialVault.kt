package com.mediahub.core.security

import kotlin.coroutines.cancellation.CancellationException

/**
 * 长期凭据保险库（ADR-016）：加密存储"原始/长期凭据"。
 *
 * 与 [TokenStore]（会话令牌）分工：
 * - TokenStore：access token（认证后生成的会话）。
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
        /** WebDAV versioned identity+password envelope; one encrypted write binds both. */
        WEBDAV_CREDENTIAL,
        API_KEY,
        REFRESH_TOKEN,
        COOKIE,
        CLIENT_SECRET,
    }

    /**
     * [clear] 的失败诊断：点名哪个种类没被清掉。
     *
     * 作为 suppressed 挂在**原始异常**上而不是替换它 —— 调用方（RemoveServerUseCase 的 runStep、
     * W6-100 的日志行）看到仍然是真实失败的类与消息，本类只补充"是哪个种类"，
     * 消息里**不含任何凭据值**。
     */
    class CredentialCleanupFailure(
        val serverId: String,
        val kind: CredentialKind,
    ) : IllegalStateException(
        "凭据清理未能持久化：serverId=" + serverId + " kind=" + kind.name,
    )

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

    /**
     * 清除某服务器的全部凭据。
     *
     * 单个种类删除失败**不会**让其余种类逃过清理（否则删源后仍残留密码 / Cookie 等长期凭据），
     * 全部种类都尝试过之后，把**首个**原始异常原样抛给调用方（异常类型与消息保持不变，
     * 既有日志行与结果页仍读得到真实失败），并在其上挂一条 [CredentialCleanupFailure]
     * suppressed 记录失败的 serverId 与种类。
     *
     * [CancellationException] 不属于失败：原样透传并立即停止（取消后不再继续删除）。
     */
    suspend fun clear(serverId: String) {
        var firstFailure: Throwable? = null
        var firstKind: CredentialKind? = null
        for (kind in CredentialKind.entries) {
            try {
                storage.remove(keyFor(serverId, kind))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                    firstKind = kind
                }
            }
        }
        val failure = firstFailure ?: return
        val kind = firstKind ?: return
        failure.addSuppressed(CredentialCleanupFailure(serverId, kind))
        throw failure
    }

    private fun keyFor(serverId: String, kind: CredentialKind) =
        "cred:" + serverId + ":" + kind.name
}
