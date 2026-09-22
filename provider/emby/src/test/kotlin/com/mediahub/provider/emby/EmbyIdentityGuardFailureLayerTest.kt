package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.MediaProbeResult
import com.mediahub.core.network.ServerProbeResult
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
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * R1 (Agent C) - layered regression for F-C1-1.
 *
 * Once the identity lease of a retained handle is revoked, three INDEPENDENT failure layers must
 * each behave correctly, and they must be asserted separately:
 *
 *  L1 raw OkHttp boundary   -> must fail with IOException (or a compatible subclass), because that
 *                              is the contract OkHttp callers and the Media3 data source handle.
 *  L2 MediaHttpClient.probe -> must RETURN MediaProbeResult.Failure. The public probe converts
 *                              failures into results, so it must not leak an unmapped runtime
 *                              exception. Asserting "probe must throw IOException" here would be
 *                              wrong: the probe owns the exception-to-result conversion.
 *  L3 ApiClient.probe       -> must RETURN ServerProbeResult.Failure, same reasoning.
 *
 * Every layer also asserts that no request reaches the previous address.
 *
 * Why this exists: the pre-existing *FactoryRestoreIsolationTest asserts only
 * runCatching { ... }.isFailure, which accepts ANY Throwable and therefore cannot constrain the
 * failure type. That is why the IllegalStateException from check(...) in the identity guard went
 * unnoticed. This test is the strict version of the same scenario.
 */
class EmbyIdentityGuardFailureLayerTest {
    private val oldEndpoint = MockWebServer()
    private val newEndpoint = MockWebServer()
    private val otherEndpoint = MockWebServer()
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

    @Before fun start() { oldEndpoint.start(); newEndpoint.start(); otherEndpoint.start() }
    @After fun close() {
        try { oldEndpoint.shutdown() } finally {
            try { newEndpoint.shutdown() } finally { otherEndpoint.shutdown() }
        }
    }

    private fun server(endpoint: MockWebServer, id: String = "source") = MediaServer(
        id = id, name = "test", type = ServerType.EMBY,
        baseUrl = endpoint.url("/").toString().trimEnd('/'), createdAtEpochMs = 0,
    )

    private suspend fun newIdentityCredentials() {
        tokens.saveTokens("source", StoredToken("FAKE-NEW-IDENTITY-TOKEN"))
        EmbySessionStore(sessions).save(EmbySession("source", "new-remote", "new-user", "new-account"))
    }

    /** Revokes the lease every retained handle captured. No network involved. */
    private suspend fun revoke() { tokens.withRestoreIdentityChange(setOf("source")) { tokens.clear("source") } }

    private fun emptyLibrary() = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody("""{"Items":[],"TotalRecordCount":0}""")

    // The base exposes these clients only to subclasses; read them without widening the Provider API.
    private fun field(handle: ProviderHandle, name: String): Any =
        BaseMediaServerProvider::class.java.getDeclaredField(name).let { f ->
            f.isAccessible = true
            f.get(handle.provider)
        }
    private fun mediaClient(handle: ProviderHandle) = field(handle, "mediaHttpClient") as MediaHttpClient
    private fun apiClient(handle: ProviderHandle) = field(handle, "apiClient") as ApiClient

    @Test fun `L1 raw http boundary fails with IOException after identity invalidation`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        revoke()
        oldEndpoint.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val thrown = try {
            mediaClient(old).okHttpClient()
                .newCall(Request.Builder().url(oldEndpoint.url("/probe")).build())
                .execute().close()
            null
        } catch (e: Throwable) { e }
        assertNotNull("撤销后的旧 handle 在原始 HTTP 边界必须失败，而不是静默成功", thrown)
        assertTrue(
            "原始 HTTP 边界必须以 IOException（或其兼容子类）失败，实际为 " +
                thrown!!::class.java.name + ": " + thrown.message,
            thrown is IOException,
        )
        assertEquals("失效后不得有请求到达旧地址", 0, oldEndpoint.requestCount)
    }

    @Test fun `L2 media probe returns a structured failure instead of throwing`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        revoke()
        oldEndpoint.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        var leaked: Throwable? = null
        val result = try {
            mediaClient(old).probe(PlaybackSource(oldEndpoint.url("/media").toString()))
        } catch (e: Throwable) {
            leaked = e
            null
        }
        assertNull(
            "MediaHttpClient.probe 负责把失败映射为结果，不得向调用者抛出未映射的运行时异常，实际抛出 " +
                (leaked?.let { it::class.java.name + ": " + it.message } ?: "null"),
            leaked,
        )
        assertTrue("probe 必须返回 MediaProbeResult.Failure，实际为 " + result, result is MediaProbeResult.Failure)
        assertEquals("失效后不得有请求到达旧地址", 0, oldEndpoint.requestCount)
    }

    @Test fun `L3 api probe returns a structured failure instead of throwing`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        revoke()
        oldEndpoint.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        var leaked: Throwable? = null
        val result = try {
            apiClient(old).probe(server(oldEndpoint).baseUrl)
        } catch (e: Throwable) {
            leaked = e
            null
        }
        assertNull(
            "ApiClient.probe 负责把失败映射为结果，不得向调用者抛出未映射的运行时异常，实际抛出 " +
                (leaked?.let { it::class.java.name + ": " + it.message } ?: "null"),
            leaked,
        )
        assertTrue("probe 必须返回 ServerProbeResult.Failure，实际为 " + result, result is ServerProbeResult.Failure)
        assertEquals("失效后不得有请求到达旧地址", 0, oldEndpoint.requestCount)
    }

    @Test fun `positive control - fresh handle and unrelated source keep working after invalidation`() = runBlocking {
        val old = factory.create(server(oldEndpoint))
        val other = factory.create(server(otherEndpoint, id = "other"))
        revoke()
        newIdentityCredentials()
        // The unrelated source needs its own credentials or its library call would fail with
        // AuthRequired and the control would be measuring the wrong thing.
        tokens.saveTokens("other", StoredToken("OTHER-IDENTITY-TOKEN"))
        val fresh = factory.create(server(newEndpoint))
        newEndpoint.enqueue(emptyLibrary())
        assertEquals("新 handle 必须可用", emptyList<Any>(), fresh.library!!.getLibraries())
        assertEquals(1, newEndpoint.requestCount)
        // Revoking one source must not break an unrelated source. Probe its media client (no token
        // required) so the control measures the identity guard, not unrelated credential presence.
        otherEndpoint.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        val otherProbe = mediaClient(other).probe(PlaybackSource(otherEndpoint.url("/media").toString()))
        assertTrue("其他来源不得因本次失效而失败，实际为 " + otherProbe, otherProbe is MediaProbeResult.Success)
        assertEquals(1, otherEndpoint.requestCount)
        assertEquals(0, oldEndpoint.requestCount)
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
