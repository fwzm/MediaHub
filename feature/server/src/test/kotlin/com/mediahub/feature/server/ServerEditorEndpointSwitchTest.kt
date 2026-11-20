package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.database.repository.ServerUserAgentRepository
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.EndpointTestResult
import com.mediahub.core.network.EndpointTestService
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.UserAgentPolicy
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 服务器编辑器线路切换（#84 部分）：
 * 备用线路被选中后成为草稿主线路，保存时只重写主线路 URL，其余线路逐字保留；
 * 停用线路与未知 id 均不得改变当前生效线路。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerEditorEndpointSwitchTest {

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class RecordingStore(initial: List<MediaServer>) : ServerStore {
        val servers = initial.toMutableList()
        val updated = mutableListOf<MediaServer>()
        override fun observeServers() = flowOf(servers.toList())
        override suspend fun getServer(id: String): MediaServer? = servers.firstOrNull { it.id == id }
        override suspend fun updateServer(server: MediaServer) {
            updated += server
            val index = servers.indexOfFirst { it.id == server.id }
            if (index >= 0) servers[index] = server else servers.add(server)
        }
        override suspend fun updateEndpointQuality(
            serverId: String,
            endpointId: String,
            expectedUrl: String,
            apiLatencyMs: Long?,
            mediaFirstByteMs: Long?,
            throughputMbps: Double?,
            protocol: String?,
            supportsRange: Boolean?,
            httpCode: Int?,
        ) = Unit
        override suspend fun setDefault(id: String) = Unit
    }

    private class FakeRemoveHandler : ServerRemoveHandler {
        override suspend fun remove(server: MediaServer) = Unit
    }

    private val factory = object : MediaProviderFactory {
        override val descriptor = ProviderDescriptor(
            id = "emby",
            serverType = ServerType.EMBY,
            displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = emptySet(),
            authMethod = AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.EXPERIMENTAL,
            probePath = "/emby/System/Info/Public",
        )
        override fun create(server: MediaServer): ProviderHandle =
            throw UnsupportedOperationException("线路切换测试不触达 Provider")
    }

    private val registry = object : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): MediaProviderFactory? =
            if (type == ServerType.EMBY) factory else null
        override fun create(server: MediaServer): ProviderHandle =
            throw UnsupportedOperationException("线路切换测试不触达 Provider")
        override val supportedTypes = setOf(ServerType.EMBY)
        override fun descriptors() = listOf(factory.descriptor)
    }

    private val noopTestService = object : EndpointTestService(HttpClientFactory(StdoutLogger())) {
        override suspend fun test(baseUrl: String, probePath: String): EndpointTestResult =
            throw UnsupportedOperationException("线路切换测试不发起探测")
    }

    private fun serverWithRoutes() = MediaServer(
        id = "srv-net",
        name = "Emby",
        type = ServerType.EMBY,
        endpoints = listOf(
            ServerEndpoint(
                id = "ep-1",
                serverId = "srv-net",
                name = "主线路",
                url = "https://media-a.example",
                isPrimary = true,
                enabled = true,
                sortOrder = 0,
            ),
            ServerEndpoint(
                id = "ep-2",
                serverId = "srv-net",
                name = "备用线路",
                url = "https://media-b.example",
                isPrimary = false,
                enabled = true,
                sortOrder = 1,
            ),
            ServerEndpoint(
                id = "ep-3",
                serverId = "srv-net",
                name = "停用线路",
                url = "https://media-c.example",
                isPrimary = false,
                enabled = false,
                sortOrder = 2,
            ),
        ),
        createdAtEpochMs = 0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val userAgentRepository = object : ServerUserAgentRepository {
        val values = mutableMapOf<String, String>()
        override val loaded: Boolean get() = true
        override fun snapshot(): Map<String, String> = values.toMap()
        override fun snapshotBlocking(timeoutMs: Long): Map<String, String> = snapshot()
        override suspend fun awaitLoaded(timeoutMs: Long): Boolean = true
        override suspend fun set(serverId: String, userAgent: String?) {
            if (userAgent.isNullOrBlank()) values.remove(serverId) else values[serverId] = userAgent
        }
        override suspend fun purgeOrphans(existingServerIds: Set<String>): Int = 0
        override suspend fun purge(serverId: String) { values.remove(serverId) }
    }

    private fun viewModel(store: ServerStore): ServerEditorViewModel = ServerEditorViewModel(
        savedStateHandle = SavedStateHandle(mapOf("serverId" to "srv-net")),
        serverStore = store,
        registry = registry,
        tokenStore = TokenStore(FakeSecretStorage()),
        serverIconStore = ServerIconStore(RuntimeEnvironment.getApplication()),
        removeHandler = FakeRemoveHandler(),
        endpointTestService = noopTestService,
        logger = StdoutLogger(),
        serverUserAgentRepository = userAgentRepository,
        userAgentPolicy = UserAgentPolicy(),
    )

    @Test
    fun `selecting backup endpoint makes it primary and saving keeps other routes`() = runTest {
        val store = RecordingStore(listOf(serverWithRoutes()))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.selectEndpoint("ep-2")
        val state = vm.uiState.value
        assertEquals("https://media-b.example", state.baseUrl)
        assertEquals("https://media-b.example", state.resolvedUrl)
        assertEquals(listOf("ep-2"), state.server!!.endpoints.filter { it.isPrimary }.map { it.id })
        assertEquals("已切换线路：备用线路", state.message)

        vm.updateBaseUrl("https://media-b2.example")
        vm.save { }
        advanceUntilIdle()

        val saved = store.updated.single()
        assertEquals("https://media-a.example", saved.endpoints[0].url)
        assertEquals("https://media-b2.example", saved.endpoints[1].url)
        assertTrue("被选线路成为主线路", saved.endpoints[1].isPrimary)
        assertFalse("旧主线路降为备用", saved.endpoints[0].isPrimary)
        assertEquals("https://media-b2.example", saved.baseUrl)
    }

    @Test
    fun `disabled endpoint cannot become primary`() = runTest {
        val store = RecordingStore(listOf(serverWithRoutes()))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.selectEndpoint("ep-2")
        vm.selectEndpoint("ep-3")
        val state = vm.uiState.value
        assertEquals("https://media-b.example", state.baseUrl)
        assertEquals(listOf("ep-2"), state.server!!.endpoints.filter { it.isPrimary }.map { it.id })
    }

    @Test
    fun `unknown endpoint selection leaves current route unchanged`() = runTest {
        val store = RecordingStore(listOf(serverWithRoutes()))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.selectEndpoint("ep-2")
        val before = vm.uiState.value
        vm.selectEndpoint("missing")
        val after = vm.uiState.value
        assertEquals("https://media-b.example", after.baseUrl)
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.server, after.server)
    }
}
