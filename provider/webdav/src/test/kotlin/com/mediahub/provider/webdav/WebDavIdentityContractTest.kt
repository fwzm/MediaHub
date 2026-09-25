package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-2 WebDAV 身份契约回归：认证条件提交 / 旧 handle 隔离 / 迟到成功不复活。
 *
 * 全部用**真实 Factory**（多 handle、共享 coordinator 与 vault）+ MockWebServer
 * 屏障构造确定性交错；断言 vault 内容、AuthResult/AuthSessionState 类型与
 * **零网络请求**（requestCount），不检查内部 generation 数值。
 */
class WebDavIdentityContractTest {

    private lateinit var webServer: MockWebServer
    private lateinit var vault: CredentialVault
    private lateinit var sharedCoordinator: WebDavCredentialCoordinator
    private lateinit var factory: WebDavProviderFactory
    private val logger = StdoutLogger()

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        val storage = FakeSecretStorage()
        vault = CredentialVault(storage)
        sharedCoordinator = WebDavCredentialCoordinator()
        factory = WebDavProviderFactory(
            HttpClientFactory(logger), TokenStore(storage), vault, sharedCoordinator, logger,
        )
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun server(username: String = "alice") = MediaServer(
        id = "s1",
        name = "NAS",
        type = ServerType.WEBDAV,
        baseUrl = webServer.url("/dav/").toString(),
        username = username,
        createdAtEpochMs = 0,
    )

    private fun multistatusOk() = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml")
        .setBody(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "root")))

    private fun vaultPassword(): String? =
        kotlinx.coroutines.runBlocking { vault.read("s1", CredentialVault.CredentialKind.PASSWORD) }

    /** A 探测在途（屏障挡住第一个请求），执行 [during]，放行后返回 A 的认证结果。 */
    private fun lateAuthA(during: suspend () -> Unit): AuthResult {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        webServer.dispatcher = object : Dispatcher() {
            var first = true
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (first) {
                    first = false
                    entered.complete(Unit)
                    assertTrue(release.await(8, TimeUnit.SECONDS))
                    return multistatusOk() // A 迟到的 2xx
                }
                return multistatusOk()
            }
        }
        val providerA = requireNotNull(factory.create(server()).auth)
        val result = arrayOfNulls<AuthResult>(1)
        val done = CountDownLatch(1)
        Thread {
            runBlocking {
                val authA = async { providerA.authenticate(Credentials.UsernamePassword("alice", "old-password")) }
                entered.await()
                during()
                release.countDown()
                result[0] = authA.await()
                done.countDown()
            }
        }.apply { start() }
        assertTrue("用例超时", done.await(15, TimeUnit.SECONDS))
        return result[0]!!
    }

    // ---- 1. 认证条件提交 ----

    @Test
    fun `late 2xx from old login does not overwrite newer login`() {
        val outcome = lateAuthA(during = {
            val providerB = requireNotNull(factory.create(server()).auth)
            val auth = providerB.authenticate(Credentials.UsernamePassword("alice", "new-password"))
            assertTrue("B 必须成功：$auth", auth is AuthResult.Success)
        })

        assertTrue("旧登录被取代应返回 Failure：$outcome", outcome is AuthResult.Failure)
        assertEquals("vault 必须保持 B 的密码", "new-password", vaultPassword())
    }

    @Test
    fun `late 2xx after logout does not resurrect credentials`() {
        val outcome = lateAuthA(during = {
            requireNotNull(factory.create(server()).auth).logout()
        })

        assertTrue("登出后的迟到成功不得复活：$outcome", outcome is AuthResult.Failure)
        assertEquals("vault 必须保持空", null, vaultPassword())
    }

    @Test
    fun `late 2xx after restore identity invalidation does not resurrect credentials`() {
        val outcome = lateAuthA(during = {
            // 备份恢复身份变更：经 invalidator 集合推进（与 TokenSessionLoginInvalidator 同路径）
            WebDavCredentialGenerationInvalidator(sharedCoordinator)
                .invalidateCredentialsGeneration("s1")
        })

        assertTrue("restore 失效后的迟到成功不得复活：$outcome", outcome is AuthResult.Failure)
        assertEquals(null, vaultPassword())
    }

    // ---- 2. 旧 restoreSession 成功不复活旧用户状态 ----

    @Test
    fun `late restore 2xx after newer login does not publish authenticated`() {
        runBlocking {
            WebDavCredentialStore(vault, sharedCoordinator).savePassword(server(), "old-password")
        }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        webServer.dispatcher = object : Dispatcher() {
            var first = true
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (first) {
                    first = false
                    entered.complete(Unit)
                    assertTrue(release.await(8, TimeUnit.SECONDS))
                    return multistatusOk()
                }
                return multistatusOk()
            }
        }
        val providerA = requireNotNull(factory.create(server()).auth)
        val result = arrayOfNulls<AuthSessionState>(1)
        val done = CountDownLatch(1)
        Thread {
            runBlocking {
                val restore = async { providerA.restoreSession() }
                entered.await()
                val providerB = requireNotNull(factory.create(server()).auth)
                assertTrue(
                    providerB.authenticate(Credentials.UsernamePassword("alice", "new-password")) is AuthResult.Success,
                )
                release.countDown()
                result[0] = restore.await()
                done.countDown()
            }
        }.apply { start() }
        assertTrue(done.await(15, TimeUnit.SECONDS))

        assertFalse("迟到成功不得发布旧 Authenticated：${result[0]}", result[0] is AuthSessionState.Authenticated)
        assertEquals("vault 保持新密码", "new-password", vaultPassword())
    }

    // ---- 3. 旧 handle 请求隔离（地址 A → B 替换） ----

    @Test
    fun `old address handle cannot read newer identity password or send to old address`() {
        val oldServer = MockWebServer().apply { start() }
        try {
            // 身份 B（当前 webServer 地址）完成认证
            val providerB = requireNotNull(factory.create(server()).auth)
            webServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = multistatusOk()
            }
            assertTrue(
                kotlinx.coroutines.runBlocking {
                    providerB.authenticate(Credentials.UsernamePassword("alice", "b-password"))
                } is AuthResult.Success,
            )

            // 身份 A 的旧 handle：server 快照指向 oldServer 地址（兼容构造：baseUrl→单主线路），
            // vault 里是 B 的密码
            val oldAddressServer = MediaServer(
                id = "s1",
                name = "NAS",
                type = ServerType.WEBDAV,
                baseUrl = oldServer.url("/dav/").toString(),
                username = "alice",
                createdAtEpochMs = 0,
            )
            val handleA = factory.create(oldAddressServer)
            val requestsBefore = oldServer.requestCount

            // browse / detail / resolvePlayback / restore 全部 fail-closed 且零网络
            val browseFailure = kotlinx.coroutines.runBlocking {
                runCatching { requireNotNull(handleA.browse).listFolder(null, PageRequest()) }.exceptionOrNull()
            }
            assertTrue("browse 必须 AuthRequired：$browseFailure", browseFailure is ProviderException.AuthRequired)

            val detailFailure = kotlinx.coroutines.runBlocking {
                runCatching {
                    requireNotNull(handleA.detail).getItemDetail(oldServer.url("/dav/movie.mkv").toString())
                }.exceptionOrNull()
            }
            assertTrue("detail 必须 AuthRequired：$detailFailure", detailFailure is ProviderException.AuthRequired)

            val playbackFailure = kotlinx.coroutines.runBlocking {
                runCatching {
                    requireNotNull(handleA.playback).resolvePlayback(
                        com.mediahub.model.MediaItem(
                            serverId = "s1",
                            id = oldServer.url("/dav/movie.mkv").toString(),
                            type = com.mediahub.model.MediaType.VIDEO,
                            title = "movie.mkv",
                        ),
                        PlaybackOptions(),
                    )
                }.exceptionOrNull()
            }
            assertTrue("playback 必须 AuthRequired：$playbackFailure", playbackFailure is ProviderException.AuthRequired)

            assertEquals(
                "旧 A handle 不得向旧地址发出任何请求（更不得带 B 的密码）",
                requestsBefore,
                oldServer.requestCount,
            )
            assertEquals("restore 也不得发请求", 0, countRestoreRequests(handleA))

            // 新 B handle 仍然可用（browse 正常打到当前地址）
            val handleB = factory.create(server())
            val page = kotlinx.coroutines.runBlocking {
                runCatching { requireNotNull(handleB.browse).listFolder(null, PageRequest()) }
            }
            assertTrue("新 handle 必须可用：${page.exceptionOrNull()}", page.isSuccess)
        } finally {
            oldServer.shutdown()
        }
    }

    private fun countRestoreRequests(handleA: com.mediahub.provider.api.ProviderHandle): Int {
        val serverBefore = webServer.requestCount
        kotlinx.coroutines.runBlocking { handleA.auth?.restoreSession() }
        // dispatcher 服务的所有请求都在当前地址上；用全局计数差值验证 restore 是否发过请求。
        // restore 读不到同身份凭据时应直接 SignedOut、零请求。
        return webServer.requestCount - serverBefore
    }

    // ---- 4. 同身份重登不破坏既有 handle（防过度阻断） ----

    @Test
    fun `same identity relogin keeps existing handle usable`() {
        webServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = multistatusOk()
        }
        val handle = factory.create(server())
        val auth = requireNotNull(handle.auth)
        assertTrue(
            kotlinx.coroutines.runBlocking {
                auth.authenticate(Credentials.UsernamePassword("alice", "first-password"))
            } is AuthResult.Success,
        )

        // 同地址同用户名重登（新密码）
        val handle2 = factory.create(server())
        assertTrue(
            kotlinx.coroutines.runBlocking {
                requireNotNull(handle2.auth).authenticate(Credentials.UsernamePassword("alice", "second-password"))
            } is AuthResult.Success,
        )

        // 旧 handle 仍可用：同身份下 browse 走 vault 当前密码（新密码），
        // 以真实请求的 Authorization 头为准（排空两次认证探测后取 browse 请求）
        val page = kotlinx.coroutines.runBlocking {
            runCatching { requireNotNull(handle.browse).listFolder(null, PageRequest()) }
        }
        assertTrue("旧 handle 同身份 browse 必须可用：${page.exceptionOrNull()}", page.isSuccess)
        var recorded: RecordedRequest? = null
        while (webServer.requestCount > 0 || webServer.takeRequest(200, TimeUnit.MILLISECONDS) != null) {
            recorded = webServer.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
        }
        assertEquals(
            "授权头必须是重登后的新密码",
            WebDavAuth.basicHeader("alice", "second-password"),
            recorded?.getHeader("Authorization"),
        )
        val restore = kotlinx.coroutines.runBlocking { auth.restoreSession() }
        assertTrue("同身份 restore 必须成功：$restore", restore is AuthSessionState.Authenticated)
    }
}
