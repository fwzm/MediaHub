package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2-1 凭据世代守卫返修：**跨 handle（跨 Factory 实例）的世代必须共享**。
 *
 * 生产中 WebDavProviderFactory 是 Hilt 单例，但 create() 每次 new 一个
 * WebDavCredentialStore；若世代表挂在 store 实例上，两个 handle（或重建后的
 * 新 handle）各自计数，迟到 401 的世代比较会形同虚设。
 *
 * 本测试用真实 Factory 创建两个实例、共享同一个 vault，复现：
 * handle A 的旧 restoreSession 在途 → handle B 重新认证成功 → A 迟到 401
 * → A 的条件清理不得删掉 B 的新密码。
 */
class WebDavCredentialSharedGenerationTest {

    private lateinit var webServer: MockWebServer
    private lateinit var vault: CredentialVault
    private lateinit var factoryA: WebDavProviderFactory
    private lateinit var factoryB: WebDavProviderFactory
    private lateinit var server: MediaServer
    private val logger = StdoutLogger()

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        val storage = FakeSecretStorage()
        vault = CredentialVault(storage)
        val tokenStore = TokenStore(storage)
        val http = HttpClientFactory(logger)
        // 两个真实 Factory 实例 + 同一个共享协调器（生产中由 Hilt @Singleton 保证）
        val sharedCoordinator = WebDavCredentialCoordinator()
        factoryA = WebDavProviderFactory(http, tokenStore, vault, sharedCoordinator, logger)
        factoryB = WebDavProviderFactory(http, tokenStore, vault, sharedCoordinator, logger)
        server = MediaServer(
            id = "s1",
            name = "NAS",
            type = ServerType.WEBDAV,
            baseUrl = webServer.url("/dav/").toString(),
            username = "alice",
            createdAtEpochMs = 0,
        )
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun multistatusOk() = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml")
        .setBody(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "root")))

    @Test
    fun `late 401 from handle A does not erase password saved via handle B`() {
        // 历史登录留下的旧密码：A 的 restoreSession 必须有凭据可读才会发出探测
        kotlinx.coroutines.runBlocking {
            vault.save("s1", CredentialVault.CredentialKind.PASSWORD, "stale-password")
        }
        val requestEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        webServer.dispatcher = object : Dispatcher() {
            var first = true
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (first) {
                    first = false
                    requestEntered.complete(Unit)
                    assertTrue(release.await(8, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(401)
                }
                return multistatusOk()
            }
        }

        val providerA = requireNotNull(factoryA.create(server).auth)
        val providerB = requireNotNull(factoryB.create(server).auth)

        val outcome = Array<Any?>(2) { null }
        val done = CountDownLatch(1)
        val thread = Thread({
            runBlocking {
                val restore = async { providerA.restoreSession() }
                // 挂起等待（不是 latch 阻塞）：让出事件循环，async 的探测请求才能发出
                requestEntered.await()
                val auth = providerB.authenticate(Credentials.UsernamePassword("alice", "fresh-password"))
                assertTrue("B 必须认证成功：$auth", auth is com.mediahub.provider.api.AuthResult.Success)
                release.countDown()
                outcome[0] = restore.await()
                outcome[1] = vault.read("s1", CredentialVault.CredentialKind.PASSWORD)
                done.countDown()
            }
        }).apply { start() }
        assertTrue("用例超时", done.await(15, TimeUnit.SECONDS))

        // A 迟到 401 后：会话失效状态 + B 的新密码不得被清掉
        assertTrue(
            "A 必须报告会话失效，实际 ${outcome[0]}",
            outcome[0] is AuthSessionState.Error,
        )
        assertEquals(
            "迟到 401 不得清掉较新身份的密码（跨 handle 世代必须共享）",
            "fresh-password",
            outcome[1],
        )
    }
}
