package com.mediahub.core.security

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TokenStoreAuthenticationTest {
    private class Storage : SecretStorage {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun get(key: String): String? = values[key]
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun contains(key: String): Boolean = values.containsKey(key)
    }

    @Test
    fun `stale commit cannot erase a newer successful authentication`() = runBlocking {
        val store = TokenStore(Storage())
        val lease = store.authenticationLease("server")
        val oldAttempt = store.beginAuthentication(lease)!!
        store.clear("server")
        val newAttempt = store.beginAuthentication(lease)!!
        var session = ""
        assertTrue(store.commitAuthentication(newAttempt, StoredToken("new"), { session = "new" }, { session = "" }))
        assertFalse(store.commitAuthentication(oldAttempt, StoredToken("old"), { fail("Stale session save") }, { fail("Stale cleanup") }))
        assertEquals("new", store.readTokens("server")?.accessToken)
        assertEquals("new", session)
    }

    @Test
    fun `session write cancellation preserves cancellation and cleans both stores`() = runBlocking {
        val store = TokenStore(Storage())
        val attempt = store.beginAuthentication(store.authenticationLease("server"))!!
        val cancellation = CancellationException("controlled cancellation")
        var session: String? = null
        val failure = try {
            store.commitAuthentication(attempt, StoredToken("partial"), {
                session = "partial"
                throw cancellation
            }, { session = null })
            null
        } catch (error: Exception) { error }
        assertSame(cancellation, failure)
        assertNull(store.readTokens("server"))
        assertNull(session)
    }

    @Test
    fun `restore waits for an atomic session commit then clears it before returning`() = runBlocking {
        val store = TokenStore(Storage())
        val attempt = store.beginAuthentication(store.authenticationLease("server"))!!
        val sessionWriteEntered = CompletableDeferred<Unit>()
        val releaseSessionWrite = CompletableDeferred<Unit>()
        val invalidationStarted = CompletableDeferred<Unit>()
        var session: String? = null
        val write = async(Dispatchers.Default) {
            store.commitAuthentication(attempt, StoredToken("before-restore"), {
                sessionWriteEntered.complete(Unit)
                releaseSessionWrite.await()
                session = "before-restore"
            }, { session = null })
        }
        val invalidate = async(Dispatchers.Default) {
            sessionWriteEntered.await()
            invalidationStarted.complete(Unit)
            store.withRestoreIdentityChange(setOf("server")) {
                store.clear("server")
                session = null
            }
        }
        try {
            withTimeout(5_000) {
                sessionWriteEntered.await()
                invalidationStarted.await()
                assertFalse("Restore cannot finish while a prior session write is in its critical section", invalidate.isCompleted)
                releaseSessionWrite.complete(Unit)
                assertTrue(write.await())
                invalidate.await()
                assertNull(store.readTokens("server"))
                assertNull(session)
            }
        } finally {
            releaseSessionWrite.complete(Unit)
            write.cancelAndJoin()
            invalidate.cancelAndJoin()
        }
    }

    @Test
    fun `cancelled restore unblocks fresh handles while old handles remain revoked`() = runBlocking {
        val store = TokenStore(Storage())
        val old = store.authenticationLease("server")
        var interim: TokenStore.AuthenticationLease? = null
        val cancellation = CancellationException("controlled restore cancellation")
        val failure = try {
            store.withRestoreIdentityChange(setOf("server")) {
                interim = store.authenticationLease("server")
                assertNull(store.beginAuthentication(old))
                assertNull(store.beginAuthentication(interim!!))
                throw cancellation
            }
        } catch (error: Exception) { error }
        assertSame(cancellation, failure)
        assertNull(store.beginAuthentication(old))
        assertNull(store.beginAuthentication(interim!!))
        assertNotNull(store.beginAuthentication(store.authenticationLease("server")))
    }

    @Test
    fun `same identity replacement with no invalidation targets preserves existing attempt`() = runBlocking {
        val store = TokenStore(Storage())
        val attempt = store.beginAuthentication(store.authenticationLease("server"))!!
        store.withRestoreIdentityChange(emptySet()) { Unit }
        var session: String? = null
        assertTrue(store.commitAuthentication(attempt, StoredToken("kept"), { session = "kept" }, { session = null }))
        assertEquals("kept", store.readTokens("server")?.accessToken)
        assertEquals("kept", session)
    }

    @Test
    fun `restoring one identity does not block unrelated authentication`() = runBlocking {
        val store = TokenStore(Storage())
        val unrelated = store.authenticationLease("other")
        store.withRestoreIdentityChange(setOf("server")) {
            val attempt = store.beginAuthentication(unrelated)!!
            assertTrue(store.commitAuthentication(attempt, StoredToken("unrelated"), {}, {}))
        }
        assertEquals("unrelated", store.readTokens("other")?.accessToken)
        assertNull(store.readTokens("server"))
    }

    @Test
    fun `late logout cleanup cannot erase a newer successful authentication`() = runBlocking {
        val store = TokenStore(Storage())
        val lease = store.authenticationLease("server")
        val oldAttempt = store.beginAuthentication(lease)!!
        store.clear("server")
        val current = store.beginAuthentication(lease)!!
        var session: String? = null
        assertTrue(store.commitAuthentication(current, StoredToken("new"), { session = "new" }, { session = null }))
        assertFalse(store.clearAuthenticationIfCurrent(oldAttempt) { fail("Stale cleanup must not run") })
        assertEquals("new", store.readTokens("server")?.accessToken)
        assertEquals("new", session)
    }

    @Test
    fun `successful same identity authentication revokes older response cleanup without an explicit clear`() = runBlocking {
        val store = TokenStore(Storage())
        val lease = store.authenticationLease("server")
        val oldResponse = store.beginAuthentication(lease)!!
        val freshLogin = store.beginAuthentication(lease)!!
        var session: String? = null
        assertTrue(store.commitAuthentication(freshLogin, StoredToken("new"), { session = "new" }, { session = null }))
        assertFalse(store.clearAuthenticationIfCurrent(oldResponse) { session = null })
        assertEquals("new", store.readTokens("server")?.accessToken)
        assertEquals("new", session)
    }

    @Test
    fun `conditional cleanup still clears session and propagates failed credential persistence`() = runBlocking {
        val storage = Storage()
        val failing = object : SecretStorage by storage {
            override suspend fun remove(key: String) { throw IllegalStateException("controlled persistence failure") }
        }
        val store = TokenStore(failing)
        val attempt = store.beginAuthentication(store.authenticationLease("server"))!!
        var session: String? = "old"
        store.saveTokens("server", StoredToken("old"))
        val failure = try { store.clearAuthenticationIfCurrent(attempt) { session = null }; null }
            catch (error: Exception) { error }
        assertNotNull(failure)
        assertNull("All cleanup targets are attempted even after one fails", session)
        assertEquals("Failed credential deletion is not reported as success", "old", store.readTokens("server")?.accessToken)
        assertFalse(store.isAuthenticationCurrent(attempt))
    }
}
