package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 装配契约：
 * - runtime capability 必须等于真实实现的能力集合，且是 descriptor 声明集合的子集（ADR-022）；
 * - descriptor 不得声明未实现的 SEARCH；
 * - 未登录 / 已登出时能力必须 fail-closed 且**零请求出网**；
 * - 协议探测不得把取消折叠为 `ConnectionStatus(false)`（ADR-039）。
 */
class WebDavProviderFactoryTest {

    private lateinit var webServer: MockWebServer
    private lateinit var storage: FakeSecretStorage
    private lateinit var tokenStore: TokenStore
    private lateinit var vault: CredentialVault
    private lateinit var http: HttpClientFactory
    private val logger = StdoutLogger()

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        storage = FakeSecretStorage()
        tokenStore = TokenStore(storage)
        vault = CredentialVault(storage)
        http = HttpClientFactory(logger)
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun factory() = WebDavProviderFactory(http, tokenStore, vault, WebDavCredentialCoordinator(), logger)

    private fun server(baseUrl: String = webServer.url("/dav/").toString()) = MediaServer(
        id = "s1",
        name = "WebDAV 测试",
        type = ServerType.WEBDAV,
        baseUrl = baseUrl,
        username = "alice",
        createdAtEpochMs = 0,
    )

    private fun enqueueBrowse() {
        webServer.enqueue(
            MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                .setBody(
                    WebDavFixtures.multistatus(
                        WebDavFixtures.collection("/dav/", "根"),
                        WebDavFixtures.file("/dav/a.mkv", length = 1, displayName = "a.mkv"),
                    )
                )
        )
    }

    @Test
    fun `handle exposes exactly the implemented capabilities`() {
        val handle = factory().create(server())

        assertEquals(
            setOf(ProviderCapability.AUTH, ProviderCapability.BROWSE, ProviderCapability.DETAIL, ProviderCapability.PLAYBACK),
            handle.runtimeCapabilities,
        )
        assertNotNull("详情能力已装配（PROPFIND Depth:0）", handle.detail)
        assertNull(handle.search)
        assertNull(handle.library)
        assertNull(handle.query)
        assertNull(handle.identityLookup)
        assertNull(handle.progress)
        assertNull(handle.subtitle)
    }

    @Test
    fun `descriptor declares no unimplemented capability`() {
        val handle = factory().create(server())
        val descriptor = handle.provider.descriptor

        assertFalse(
            "只读包不实现远端搜索，不得声明 SEARCH",
            descriptor.declaredCapabilities.contains(ProviderCapability.SEARCH),
        )
        assertTrue(descriptor.declaredCapabilities.containsAll(handle.runtimeCapabilities))
        assertNull("不得虚构 GET 探针路径", descriptor.probePath)
        assertEquals("webdav", descriptor.id)
    }

    @Test
    fun `authenticated handle browses end to end`() = runBlocking {
        val handle = factory().create(server())
        vault.save("s1", CredentialVault.CredentialKind.PASSWORD, "pw")
        enqueueBrowse()

        val result = handle.browse!!.listFolder(null, PageRequest())

        assertEquals(listOf("a.mkv"), result.items.map { it.title })
        assertEquals(1, webServer.requestCount)
    }

    @Test
    fun `logged out handle fails closed without any request`() = runBlocking {
        val handle = factory().create(server())
        vault.save("s1", CredentialVault.CredentialKind.PASSWORD, "pw")
        handle.auth!!.logout()

        val failure = runCatching {
            handle.browse!!.listFolder(null, PageRequest())
        }.exceptionOrNull()

        assertTrue("实际：$failure", failure is ProviderException.AuthRequired)
        assertEquals("登出后不得发出任何请求", 0, webServer.requestCount)
    }

    @Test
    fun `test connection probes OPTIONS and reports DAV header`() = runBlocking {
        val handle = factory().create(server())
        webServer.enqueue(MockResponse().setResponseCode(200).setHeader("DAV", "1, 2, 3"))

        val status = handle.provider.testConnection()

        assertTrue("实际：$status", status.ok)
        assertTrue(status.message!!.contains("DAV"))
        assertEquals("OPTIONS", webServer.takeRequest().method)
    }

    @Test
    fun `test connection does not fold cancellation into failure status`() = runBlocking {
        val cancelling = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("scope cancelled") }
            .build()
        val srv = server()
        val provider = WebDavProvider(
            server = srv,
            apiClient = ApiClient(http.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(cancelling, logger),
            tokenStore = tokenStore,
            logger = logger,
            credentialVault = vault,
            credentialCoordinator = WebDavCredentialCoordinator(),
        )

        val thrown = runCatching { provider.testConnection() }.exceptionOrNull()
        assertTrue("取消必须穿透，实际：$thrown", thrown is CancellationException)
    }

    @Test
    fun `registry level descriptor matches factory descriptor`() {
        assertEquals(WEBDAV_PROVIDER_DESCRIPTOR, factory().descriptor)
    }
}
