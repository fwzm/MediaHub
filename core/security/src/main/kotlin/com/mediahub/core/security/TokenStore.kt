package com.mediahub.core.security

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 某数据源服务器的会话 Token（仅内存使用，落库时加密）。 */
data class StoredToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
)

/**
 * 按 serverId 管理 Token 的加密存取。
 * 存储格式（不依赖 JSON 库）：base64(access) | base64(refresh) | expires
 *
 * 说明：使用 java.util.Base64（API 26+ 可用），保证 JVM 单测可运行；
 * 输出格式与旧版（android.util.Base64 NO_WRAP）兼容。
 */
class TokenStore(private val storage: SecretStorage) {

    /** Bound to a Provider instance; restore revokes old instances, ordinary logout does not. */
    class AuthenticationLease internal constructor(
        internal val owner: TokenStore,
        internal val serverId: String,
        internal val restoreGeneration: Long,
        internal val allowed: Boolean,
    )

    class AuthenticationAttempt internal constructor(
        internal val lease: AuthenticationLease,
        internal val epoch: Long,
    )

    private data class RestoreStatus(val generation: Long = 0, val blocked: Boolean = false)
    private class AuthState {
        val mutex = Mutex()
        var epoch = 0L
        var restoreBlocks = 0
        @Volatile var restoreStatus = RestoreStatus()
    }
    private val authStates = ConcurrentHashMap<String, AuthState>()
    private fun state(serverId: String): AuthState = authStates.getOrPut(serverId) { AuthState() }

    /** A handle created during restoration stays invalid even after the block ends. */
    fun authenticationLease(serverId: String): AuthenticationLease {
        val status = state(serverId).restoreStatus
        return AuthenticationLease(this, serverId, status.generation, !status.blocked)
    }

    /** Non-blocking request preflight for the private clients of one Provider handle. */
    fun isAuthenticationLeaseCurrent(lease: AuthenticationLease): Boolean {
        require(lease.owner === this)
        val status = state(lease.serverId).restoreStatus
        return lease.allowed && !status.blocked && lease.restoreGeneration == status.generation
    }

    suspend fun isAuthenticationCurrent(attempt: AuthenticationAttempt): Boolean {
        require(attempt.lease.owner === this)
        val state = state(attempt.lease.serverId)
        return state.mutex.withLock { attempt.lease.isCurrent(state) && attempt.epoch == state.epoch }
    }

    /** A late 401/logout must never clear credentials established by a newer authentication. */
    suspend fun clearAuthenticationIfCurrent(attempt: AuthenticationAttempt, clearSession: suspend () -> Unit): Boolean {
        require(attempt.lease.owner === this)
        val state = state(attempt.lease.serverId)
        return withContext(NonCancellable) {
            state.mutex.withLock {
                if (!attempt.lease.isCurrent(state) || attempt.epoch != state.epoch) return@withLock false
                state.epoch++
                var failure: Exception? = null
                try { storage.remove(keyFor(attempt.lease.serverId)) } catch (error: Exception) { failure = error }
                try { clearSession() } catch (error: Exception) { if (failure == null) failure = error }
                failure?.let { throw it }
                true
            }
        }
    }

    /** Capture before starting the network request. No mutex is held during network IO. */
    suspend fun beginAuthentication(lease: AuthenticationLease): AuthenticationAttempt? {
        require(lease.owner === this)
        val state = state(lease.serverId)
        return state.mutex.withLock {
            if (lease.isCurrent(state)) AuthenticationAttempt(lease, state.epoch) else null
        }
    }

    /** Token/session writes and failure cleanup share the same lock as clear/restore revocation. */
    suspend fun commitAuthentication(
        attempt: AuthenticationAttempt,
        tokens: StoredToken,
        saveSession: suspend () -> Unit,
        clearSession: suspend () -> Unit,
    ): Boolean {
        require(attempt.lease.owner === this)
        val serverId = attempt.lease.serverId
        val state = state(serverId)
        return state.mutex.withLock {
            if (!attempt.lease.isCurrent(state) || attempt.epoch != state.epoch) return@withLock false
            currentCoroutineContext().ensureActive()
            try {
                storage.put(keyFor(serverId), encode(tokens))
                saveSession()
                currentCoroutineContext().ensureActive()
                state.epoch++ // Responses from an earlier session may no longer clear this login.
                true
            } catch (failure: Exception) {
                // Do not re-enter clear(): its lock is already held, and no later session may be erased.
                withContext(NonCancellable) {
                    try { storage.remove(keyFor(serverId)) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    try { clearSession() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }

    /** Block authentication through address writes/rollback and revoke handles from the old identity. */
    suspend fun <T> withRestoreIdentityChange(serverIds: Set<String>, block: suspend () -> T): T {
        val entered = mutableListOf<AuthState>()
        try {
            for (serverId in serverIds.sorted()) {
                val state = state(serverId)
                state.mutex.withLock {
                    state.epoch++
                    state.restoreBlocks++
                    state.restoreStatus = RestoreStatus(state.restoreStatus.generation + 1, blocked = true)
                    entered += state
                }
            }
            return block()
        } finally {
            withContext(NonCancellable) {
                for (state in entered.asReversed()) state.mutex.withLock {
                    state.restoreBlocks--
                    state.restoreStatus = state.restoreStatus.copy(blocked = state.restoreBlocks > 0)
                }
            }
        }
    }

    private fun AuthenticationLease.isCurrent(state: AuthState): Boolean =
        allowed && !state.restoreStatus.blocked && restoreGeneration == state.restoreStatus.generation

    suspend fun saveTokens(serverId: String, tokens: StoredToken) {
        state(serverId).mutex.withLock { storage.put(keyFor(serverId), encode(tokens)) }
    }

    suspend fun readTokens(serverId: String): StoredToken? = state(serverId).mutex.withLock {
        storage.get(keyFor(serverId))?.let(::decode)
    }

    suspend fun clear(serverId: String) {
        val state = state(serverId)
        state.mutex.withLock {
            state.epoch++
            storage.remove(keyFor(serverId))
        }
    }

    private fun keyFor(serverId: String) = "token:$serverId"

    private fun encode(tokens: StoredToken): String {
        val encoder = Base64.getEncoder()
        val access = encoder.encodeToString(tokens.accessToken.toByteArray(Charsets.UTF_8))
        val refresh = encoder.encodeToString((tokens.refreshToken ?: "").toByteArray(Charsets.UTF_8))
        val expires = tokens.expiresAtEpochMs ?: -1L
        return "$access|$refresh|$expires"
    }

    private fun decode(raw: String): StoredToken? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        return try {
            val decoder = Base64.getDecoder()
            val access = String(decoder.decode(parts[0]), Charsets.UTF_8)
            val refreshRaw = decoder.decode(parts[1])
            val refresh = if (refreshRaw.isEmpty()) null else String(refreshRaw, Charsets.UTF_8)
            val expires = parts[2].toLongOrNull()?.takeIf { it > 0 }
            StoredToken(accessToken = access, refreshToken = refresh, expiresAtEpochMs = expires)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
