package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.session.EmbySessionStore
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 (Agent C) - bounded, SEEDED interleaving budget over the production TokenStore + EmbyAuthProvider.
 *
 * Why this exists: the existing *AuthProviderTest already proves single interleavings (one barrier, one
 * release). What was missing is (a) a budgeted run that interleaves "revoked" and "not revoked" under a
 * FIXED seed, (b) assertions that separate three different facts instead of collapsing them:
 *   - NETWORK     : did the request really reach the server (requestCount), and is it zero new requests
 *                   on the blocked path;
 *   - BACKGROUND  : did the off-thread login coroutine actually finish;
 *   - STATE COMMIT: were tokens/session persisted or not.
 * and (c) reporting EVERY failure of the run instead of aborting at the first one.
 *
 * Discipline: the budget is fixed in advance (ROUNDS), the seed is fixed and printed in the failure
 * message, resources are always released in finally, and no unbounded advanceUntilIdle / no global
 * exception-policy change is used. A flaky or failing round is RECORDED, never retried until green.
 */
class EmbyAuthenticationInterleavingStressTest {

    private companion object {
        const val SEED = 20260913L
        const val ROUNDS = 16
        const val GATE_TIMEOUT_SEC = 5L
        const val BODY = """{"User":{"Id":"stress-user","Name":"Stress"},"AccessToken":"STRESS-TOKEN","ServerId":"emby-remote-1"}"""
    }

    @Test
    fun `seeded interleaving budget separates network completion and state commit`() = runBlocking {
        val random = Random(SEED)
        val failures = mutableListOf<String>()
        var revokedRounds = 0
        repeat(ROUNDS) { round ->
            // The decision is drawn from the seeded stream BEFORE the round so a re-run reproduces it.
            val revoked = random.nextBoolean()
            if (revoked) revokedRounds++
            runRound(round, revoked, failures)
        }
        assertTrue(
            "seed=" + SEED + " rounds=" + ROUNDS + " revoked=" + revokedRounds +
                " 失败 " + failures.size + " 处：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private suspend fun runRound(round: Int, revoked: Boolean, failures: MutableList<String>) {
        val server = MockWebServer()
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val serverId = "stress-$round"
        val tokenStore = TokenStore(MemorySecrets())
        val sessionStore = EmbySessionStore(MemorySessions())
        server.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    if (!releaseResponse.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    return MockResponse().setResponseCode(200).setBody(BODY)
                }
            }
            coroutineScope {
                val provider = providerFor(server, serverId, tokenStore, sessionStore)
                val job = async(Dispatchers.Default) {
                    provider.authenticate(Credentials.UsernamePassword("stress", "pw"))
                }
                // NETWORK: prove the request really left the process before interfering.
                if (!withTimeout(15_000) { requestStarted.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS) }) {
                    failures += "round=$round revoked=$revoked 真实登录请求未到达服务端（barrier 超时）"
                    return@coroutineScope
                }
                if (revoked) {
                    tokenStore.withRestoreIdentityChange(setOf(serverId)) { tokenStore.clear(serverId) }
                }
                releaseResponse.countDown()
                val result = withTimeout(20_000) { job.await() }
                // BACKGROUND: the off-thread work must have ended.
                if (!job.isCompleted) failures += "round=$round 后台登录协程未结束"
                // STATE COMMIT: asserted separately from the network and thread facts.
                val token = tokenStore.readTokens(serverId)
                val session = sessionStore.read(serverId)
                if (revoked) {
                    if (result is AuthResult.Success) failures += "round=$round 撤销后迟到的登录被当作成功"
                    if (token != null) failures += "round=$round 撤销后旧响应写回了 Token"
                    if (session != null) failures += "round=$round 撤销后旧响应写回了会话"
                } else {
                    if (result !is AuthResult.Success) failures += "round=$round 正常路径登录失败: " + result
                    if (token?.accessToken != "STRESS-TOKEN") failures += "round=$round 正常路径未提交 Token"
                    if (session == null) failures += "round=$round 正常路径未提交会话"
                }
                if (server.requestCount != 1) {
                    failures += "round=$round 请求数=" + server.requestCount + " 期望=1"
                }
            }
        } catch (t: Throwable) {
            failures += "round=$round revoked=$revoked 抛出 " + t::class.java.name + ": " + t.message
        } finally {
            releaseResponse.countDown()
            runCatching { server.shutdown() }
        }
    }

    private fun providerFor(
        server: MockWebServer,
        serverId: String,
        tokenStore: TokenStore,
        sessionStore: EmbySessionStore,
    ): EmbyAuthProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "stress-device", "0.1.0")
        val client = OkHttpClient.Builder()
            .connectTimeout(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyAuthProvider(
            MediaServer(id = serverId, name = "stress", type = ServerType.EMBY,
                baseUrl = server.url("/").toString().trimEnd('/'), createdAtEpochMs = 0),
            api, tokenStore, sessionStore, logger,
        )
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
