package com.mediahub.feature.settings.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.mediahub.core.logging.CompositeLogger
import com.mediahub.core.security.KeystoreSecretStorage
import com.mediahub.provider.emby.session.EmbySessionStore
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses each production store; only SharedPreferences commit failure is injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionCredentialRemovalTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `secret deletion commits before returning and preserves unrelated records`() = runBlocking {
        verifySuccess("mediahub_secret_store") { wrapped ->
            KeystoreSecretStorage(wrapped, CompositeLogger(emptyList())).remove(KEY)
        }
    }

    @Test
    fun `secret deletion failure is reported without asynchronous success`() = runBlocking {
        verifyFailure("mediahub_secret_store") { wrapped ->
            KeystoreSecretStorage(wrapped, CompositeLogger(emptyList())).remove(KEY)
        }
    }

    @Test
    fun `emby session deletion commits before returning and preserves unrelated records`() = runBlocking {
        verifySuccess("mediahub_emby_sessions") { wrapped ->
            EmbySessionStore(EmbySessionStore.SharedPrefsStorage(wrapped)).clear(KEY)
        }
    }

    @Test
    fun `emby session deletion failure is reported without asynchronous success`() = runBlocking {
        verifyFailure("mediahub_emby_sessions") { wrapped ->
            EmbySessionStore(EmbySessionStore.SharedPrefsStorage(wrapped)).clear(KEY)
        }
    }

    @Test
    fun `jellyfin session deletion commits before returning and preserves unrelated records`() = runBlocking {
        verifySuccess("mediahub_jellyfin_sessions") { wrapped ->
            JellyfinSessionStore(JellyfinSessionStore.SharedPrefsStorage(wrapped)).clear(KEY)
        }
    }

    @Test
    fun `jellyfin session deletion failure is reported without asynchronous success`() = runBlocking {
        verifyFailure("mediahub_jellyfin_sessions") { wrapped ->
            JellyfinSessionStore(JellyfinSessionStore.SharedPrefsStorage(wrapped)).clear(KEY)
        }
    }

    private suspend fun verifySuccess(name: String, remove: suspend (Context) -> Unit) {
        val probe = probe(name, failCommit = false)
        remove(probe.context)
        assertEquals("Removal must finish its durable write", 1, probe.commitCalls)
        assertEquals("Asynchronous apply cannot establish an invalidation boundary", 0, probe.applyCalls)
        assertNull(probe.preferences.getString(KEY, null))
        assertEquals(OTHER_VALUE, probe.preferences.getString(OTHER_KEY, null))
    }

    private suspend fun verifyFailure(name: String, remove: suspend (Context) -> Unit) {
        val probe = probe(name, failCommit = true)
        val failure = try {
            remove(probe.context)
            null
        } catch (exception: Exception) {
            exception
        }
        assertNotNull("A failed durable invalidation must stop restore before identity changes", failure)
        assertEquals(1, probe.commitCalls)
        assertEquals(0, probe.applyCalls)
        assertEquals(OLD_VALUE, probe.preferences.getString(KEY, null))
        assertEquals(OTHER_VALUE, probe.preferences.getString(OTHER_KEY, null))
        assertFalse(failure.toString().contains(OLD_VALUE))
        assertFalse(failure.toString().contains(KEY))
    }

    private fun probe(name: String, failCommit: Boolean): RemovalProbe {
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        check(preferences.edit().clear().putString(KEY, OLD_VALUE).putString(OTHER_KEY, OTHER_VALUE).commit())
        return RemovalProbe(context, name, preferences, failCommit)
    }

    private class RemovalProbe(
        base: Context,
        private val name: String,
        val preferences: SharedPreferences,
        private val failCommit: Boolean,
    ) {
        var commitCalls = 0
            private set
        var applyCalls = 0
            private set
        private val wrappedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun remove(key: String?): SharedPreferences.Editor {
                        delegate.remove(key)
                        return this
                    }

                    override fun commit(): Boolean {
                        commitCalls++
                        return if (failCommit) false else delegate.commit()
                    }

                    override fun apply() {
                        applyCalls++
                        delegate.apply()
                    }
                }
            }
        }
        val context: Context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                check(name == this@RemovalProbe.name)
                return wrappedPreferences
            }
        }
    }

    private companion object {
        const val KEY = "private-server-key"
        const val OLD_VALUE = "PRIVATE_OLD_CREDENTIAL_OR_SESSION"
        const val OTHER_KEY = "other-server-key"
        const val OTHER_VALUE = "OTHER_SESSION_MUST_REMAIN"
    }
}
