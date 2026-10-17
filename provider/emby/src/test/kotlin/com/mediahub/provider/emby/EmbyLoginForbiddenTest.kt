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
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.session.EmbySessionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Emby 登录失败语义：**403 不是密码错误**。
 *
 * 真实缺陷（真机复现）：墨云阁 Emby 4.9.3.0 —— 密码正确但服务端返回 403，
 * UI 却显示「用户名或密码错误」，把用户引向错误的自查方向（反复重输密码）。
 * 已用只读探测确认该服务器的 `/emby/System/Info/Public` 返回 200（服务器可达、
 * 类型为 Emby 4.9.3.0），且 Emby 对**凭据错误**返回 **401**。
 *
 * 契约：401 = 凭据错误；403 = 访问策略拒绝（账号禁用 / 不允许远程访问 /
 * IP 或反代白名单 / 权限不足），必须保留 HTTP 语义。
 * Jellyfin 侧早已按此修正，Emby 侧此前遗漏 —— 本测试固定该契约防回归。
 */
class EmbyLoginForbiddenTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: EmbyAuthProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "test-device-forbidden", "0.1.0")
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        val mediaServer = MediaServer(
            id = "srv-forbidden-1",
            name = "墨云阁",
            type = ServerType.EMBY,
            baseUrl = "http://localhost",
            createdAtEpochMs = 0,
        )
        provider = EmbyAuthProvider(
            server = mediaServer,
            api = api,
            tokenStore = TokenStore(InMemorySecretStorage()),
            sessionStore = EmbySessionStore(InMemorySessionStorage()),
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login 403 is not reported as wrong password`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = provider.authenticate(Credentials.UsernamePassword("user", "correct-password"))
        val failure = result as AuthResult.Failure
        assertFalse(
            "403 不得映射为凭据错误（实际：${failure.error}）",
            failure.error is ProviderException.AuthFailed,
        )
        assertTrue(
            "403 必须保留 HTTP 语义（实际：${failure.error}）",
            failure.error is ProviderException.Http &&
                (failure.error as ProviderException.Http).statusCode == 403,
        )
    }

    private class InMemorySecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class InMemorySessionStorage : EmbySessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `login 401 is still reported as wrong password`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = provider.authenticate(Credentials.UsernamePassword("user", "wrong"))
        val failure = result as AuthResult.Failure
        assertTrue(
            "401 才是凭据错误（实际：${failure.error}）",
            failure.error is ProviderException.AuthFailed,
        )
    }
}