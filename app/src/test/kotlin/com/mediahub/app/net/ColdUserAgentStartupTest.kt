package com.mediahub.app.net

import com.mediahub.app.di.AppModule
import com.mediahub.app.image.ProviderImageAuthStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.network.MediaHubUserAgent
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import com.mediahub.core.network.ServerIdResolver
import com.mediahub.core.network.UserAgentInterceptor
import com.mediahub.core.network.UserAgentPolicy
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ColdUserAgentStartupTest {
    private fun server(id: String, url: HttpUrl) = MediaServer(
        id = id, name = "Controlled source", type = ServerType.WEBDAV, createdAtEpochMs = 0,
        endpoints = listOf(ServerEndpoint(id = "$id-endpoint", serverId = id, url = url.toString(), name = "fixture")),
    )
    private fun store(records: List<MediaServer>) = object : ServerStore {
        override fun observeServers() = flowOf(records)
        override suspend fun getServer(id: String) = records.firstOrNull { it.id == id }
    }
    private class GatedStore : ServerStore {
        val gate = CompletableDeferred<List<MediaServer>>()
        val collecting = CountDownLatch(1)
        val directReads = AtomicInteger()
        override fun observeServers(): Flow<List<MediaServer>> = flow {
            collecting.countDown()
            emit(gate.await())
            awaitCancellation()
        }
        override suspend fun getServer(id: String): MediaServer? {
            directReads.incrementAndGet(); error("No request-thread repository I/O allowed")
        }
    }
    /** Uses the actual composition-root resolver and production network interceptors. */
    private fun coldRequest(request: Request, records: List<MediaServer>): Boolean {
        val source = GatedStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val index = ServerUrlIndex(source, scope)
            assertTrue(source.collecting.await(2, TimeUnit.SECONDS))
            val auth = ProviderImageAuthStore(store(emptyList()), emptySet())
            val production = AppModule.provideServerIdResolver(index, auth)
            val entered = CountDownLatch(1); val resolved = CountDownLatch(1)
            val resolver = ServerIdResolver { url ->
                entered.countDown()
                production.serverIdFor(url).also { resolved.countDown() }
            }
            val policy = UserAgentPolicy(overrides = { mapOf("source-a" to "Fixture-UA/1") })
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addNetworkInterceptor(OriginScopedCredentialInterceptor())
                .addNetworkInterceptor(UserAgentInterceptor(policy, resolver)).build()
            val response = worker.submit { client.newCall(request).execute().use { assertEquals(200, it.code) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val returnedBeforeSnapshot = resolved.await(60, TimeUnit.MILLISECONDS)
            source.gate.complete(records)
            response.get(5, TimeUnit.SECONDS)
            assertEquals(0, source.directReads.get())
            return returnedBeforeSnapshot
        } finally { scope.cancel(); worker.shutdownNow() }
    }
    @Test fun `first cold OPTIONS uses the saved scope after its initial snapshot arrives`() {
        val upstream = MockWebServer(); upstream.start()
        try {
            upstream.enqueue(MockResponse().setResponseCode(200))
            val early = coldRequest(Request.Builder().url(upstream.url("/dav/")).method("OPTIONS", null).build(),
                listOf(server("source-a", upstream.url("/dav/"))))
            val received = requireNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("OPTIONS", received.method)
            assertEquals("Fixture-UA/1", received.getHeader("User-Agent"))
            assertFalse("First scope decision must not escape before the persisted snapshot", early)
        } finally { upstream.shutdown() }
    }
    @Test fun `cold redirect keeps custom UA inside scope then defaults on a foreign origin`() {
        val upstream = MockWebServer(); val foreign = MockWebServer(); upstream.start(); foreign.start()
        try {
            upstream.enqueue(MockResponse().setResponseCode(302).addHeader("Location", upstream.url("/dav/inside")))
            upstream.enqueue(MockResponse().setResponseCode(302).addHeader("Location", foreign.url("/outside")))
            foreign.enqueue(MockResponse().setResponseCode(200))
            coldRequest(Request.Builder().url(upstream.url("/dav/start"))
                .header("Authorization", "Bearer controlled-fixture").build(),
                listOf(server("source-a", upstream.url("/dav/"))))
            val first = requireNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
            val same = requireNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
            val other = requireNotNull(foreign.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("Fixture-UA/1", first.getHeader("User-Agent"))
            assertEquals("Fixture-UA/1", same.getHeader("User-Agent"))
            assertEquals(MediaHubUserAgent.DEFAULT, other.getHeader("User-Agent"))
            assertEquals("Bearer controlled-fixture", same.getHeader("Authorization"))
            assertNull(other.getHeader("Authorization"))
        } finally { upstream.shutdown(); foreign.shutdown() }
    }
    @Test fun `ambiguous current index cannot be bypassed by an older unique image-auth snapshot`() {
        val url = "https://fixture.invalid/dav/movie.mkv".toHttpUrl()
        val a = server("source-a", url.resolve("/dav/")!!)
        val b = server("source-b", url.resolve("/dav/")!!)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val index = ServerUrlIndex(store(listOf(a, b)), scope)
            val auth = ProviderImageAuthStore(store(listOf(a)), emptySet()).also { it.onServersChanged(listOf(a)) }
            assertNull(index.serverIdFor(url))
            assertEquals("source-a", auth.serverIdForUrl(url))
            assertNull(AppModule.provideServerIdResolver(index, auth).serverIdFor(url))
        } finally { scope.cancel() }
    }
    @Test fun `missing snapshot has a bounded request-thread wait and performs no direct reads`() {
        val source = GatedStore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val index = ServerUrlIndex(source, scope)
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("fixture.invalid").build()
            val result = worker.submit<ServerUrlIndex.Lookup> { index.lookupAfterStartup(url, 20) }
                .get(2, TimeUnit.SECONDS)
            assertNull(result.serverId); assertFalse(result.matched); assertFalse(index.loaded)
            assertFalse(result.ready)
            assertEquals(0, source.directReads.get())
            source.gate.complete(listOf(server("source-a", url)))
            assertEquals("source-a", index.lookupAfterStartup(url, 1_000).serverId)
        } finally { scope.cancel(); worker.shutdownNow() }
    }
    @Test fun `failed primary scope does not borrow a warmed image-auth mapping`() {
        val url = "https://fixture.invalid/dav/movie.mkv".toHttpUrl()
        val old = server("source-a", url.resolve("/dav/")!!)
        val broken = object : ServerStore {
            override fun observeServers(): Flow<List<MediaServer>> = flow { throw java.io.IOException("controlled unavailable") }
            override suspend fun getServer(id: String): MediaServer? = error("No direct reads")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val index = ServerUrlIndex(broken, scope)
            val auth = ProviderImageAuthStore(store(listOf(old)), emptySet()).also { it.onServersChanged(listOf(old)) }
            assertEquals("source-a", auth.serverIdForUrl(url))
            val actual = AppModule.provideServerIdResolver(index, auth)
            assertNull(worker.submit<String?> { actual.serverIdFor(url) }.get(1, TimeUnit.SECONDS))
            assertFalse(index.loaded)
        } finally { scope.cancel(); worker.shutdownNow() }
    }
    @Test fun `interrupted startup wait preserves the caller interrupt flag`() {
        val source = GatedStore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val index = ServerUrlIndex(source, scope)
            val url = "https://fixture.invalid/dav/".toHttpUrl()
            val preserved = worker.submit<Boolean> {
                Thread.currentThread().interrupt()
                try {
                    assertFalse(index.lookupAfterStartup(url, 5_000).ready)
                    Thread.currentThread().isInterrupted
                } finally { Thread.interrupted() }
            }.get(1, TimeUnit.SECONDS)
            assertTrue(preserved)
            assertEquals(0, source.directReads.get())
        } finally { scope.cancel(); worker.shutdownNow() }
    }
}
