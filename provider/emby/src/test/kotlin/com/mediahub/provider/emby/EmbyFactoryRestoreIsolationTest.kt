package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.MediaProbeResult
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.base.BaseMediaServerProvider
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise real clients built by the production factory, including credentials read after restore. */
class EmbyFactoryRestoreIsolationTest {
    private val oldEndpoint = MockWebServer()
    private val newEndpoint = MockWebServer()
    private val tokens = TokenStore(MemorySecrets())
    private val sessions = MemorySessions()
    private val logger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
    private val factory = EmbyProviderFactory(HttpClientFactory(logger), tokens,
        ClientIdentity("MediaHub", "test", "test-device", "test"), sessionStoreStorage = sessions, logger = logger)

    @Before fun start() { oldEndpoint.start(); newEndpoint.start() }
    @After fun close() { try { oldEndpoint.shutdown() } finally { newEndpoint.shutdown() } }

    private fun server(endpoint: MockWebServer) = MediaServer(
        id = "source", name = "test", type = ServerType.EMBY,
        baseUrl = endpoint.url("/").toString().trimEnd('/'), createdAtEpochMs = 0,
    )
    private suspend fun newIdentityCredentials() {
        tokens.saveTokens("source", StoredToken("FAKE-NEW-IDENTITY-TOKEN"))
        EmbySessionStore(sessions).save(EmbySession("source", "new-remote", "new-user", "new-account"))
    }
    private fun emptyLibrary() = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody("""{"Items":[],"TotalRecordCount":0}""")

    @Test fun `old factory handle cannot send new identity token to its captured address`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        tokens.withRestoreIdentityChange(setOf("source")) { tokens.clear("source") }
        newIdentityCredentials()
        oldEndpoint.enqueue(emptyLibrary())
        newEndpoint.enqueue(emptyLibrary())
        val staleResult = runCatching { old.library!!.getLibraries() }
        assertEquals("A retained old handle must never reach its previous address", 0, oldEndpoint.requestCount)
        assertTrue(staleResult.isFailure)
        val fresh = factory.create(server(newEndpoint))
        assertEquals(emptyList<Any>(), fresh.library!!.getLibraries())
        assertEquals(1, newEndpoint.requestCount)
        assertEquals("FAKE-NEW-IDENTITY-TOKEN", tokens.readTokens("source")?.accessToken)
    }

    @Test fun `handle assembled during identity replacement stays unusable after it ends`() = runBlocking {
        lateinit var transient: ProviderHandle
        tokens.withRestoreIdentityChange(setOf("source")) {
            transient = factory.create(server(oldEndpoint))
        }
        newIdentityCredentials()
        oldEndpoint.enqueue(emptyLibrary())
        val result = runCatching { transient.library!!.getLibraries() }
        assertEquals("A handle created against an intermediate address cannot become usable", 0, oldEndpoint.requestCount)
        assertTrue(result.isFailure)
        assertEquals("FAKE-NEW-IDENTITY-TOKEN", tokens.readTokens("source")?.accessToken)
    }

    @Test fun `factory media client also rejects stale handles while a fresh client works`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        tokens.withRestoreIdentityChange(setOf("source")) { tokens.clear("source") }
        newIdentityCredentials()
        oldEndpoint.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        newEndpoint.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        val oldResult = runCatching {
            mediaClient(old).probe(PlaybackSource(oldEndpoint.url("/media").toString(),
                headers = mapOf("X-Emby-Token" to "FAKE-NEW-IDENTITY-TOKEN")))
        }
        assertEquals(0, oldEndpoint.requestCount)
        assertTrue(oldResult.isFailure)
        val fresh = factory.create(server(newEndpoint))
        val result = mediaClient(fresh).probe(PlaybackSource(newEndpoint.url("/media").toString()))
        assertTrue(result is MediaProbeResult.Success)
        assertEquals(1, newEndpoint.requestCount)
    }

    // The existing base exposes this client only to subclasses. Inspect its assembled dependency
    // without expanding the public Provider API solely for a test.
    private fun mediaClient(handle: ProviderHandle): MediaHttpClient =
        BaseMediaServerProvider::class.java.getDeclaredField("mediaHttpClient").let { field ->
            field.isAccessible = true
            field.get(handle.provider) as MediaHttpClient
        }

    private class MemorySecrets : SecretStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun get(key: String): String? = values[key]
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun contains(key: String): Boolean = values.containsKey(key)
    }
    private class MemorySessions : EmbySessionStore.Storage {
        private val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { values[key] = value }
        override fun get(key: String): String? = values[key]
        override fun remove(key: String) { values.remove(key) }
    }
}
