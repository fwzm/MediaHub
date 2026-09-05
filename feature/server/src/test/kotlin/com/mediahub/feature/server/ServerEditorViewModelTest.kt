package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.EndpointTestResult
import com.mediahub.core.network.EndpointTestService
import com.mediahub.core.network.HttpClientFactory
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
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ServerEditorViewModel 回归（Phase 1I review P1/P2，调用端真实 VM 测试）：
 *
 * - LOCAL 媒体源元数据编辑不被网络地址校验拦截（endpoints 原样保留）；
 * - 线路质量测试结果按"草稿版本 + 持久化目标归属"双闸隔离：
 *   A 测试途中改为 B / 切换 HTTPS → 旧结果不显示也不落库；
 *   未保存草稿 B 的测试结果只展示，不覆盖已保存 A 的质量数据；
 *   已保存地址的测试正常展示并落库（防过度阻断）。
 *
 * 可控延迟：FakeEndpointTestService 以 inFlight/release 两个 deferred 精确控制
 * 在途窗口，配合 StandardTestDispatcher 虚拟时间确定性推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerEditorViewModelTest {

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    class FakeEndpointTestService : EndpointTestService(HttpClientFactory(StdoutLogger())) {
        private class Pending {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        private val pendings = mutableListOf<Pending>()
        var calls: Int = 0
            private set

        /** 模拟阻塞 IO：job 取消后 release.await() 不受影响（NonCancellable），返回时上层才以取消收场。 */
        var ignoreCancellation = false

        fun enqueueTest() { pendings += Pending() }

        suspend fun awaitEntered() = pendings.last().entered.await()

        fun releaseLast() { pendings.last().release.complete(Unit) }

        override suspend fun test(baseUrl: String, probePath: String): EndpointTestResult {
            calls += 1
            val pending = pendings[calls - 1]
            pending.entered.complete(Unit)
            if (ignoreCancellation) {
                // 模拟阻塞 IO 晚返回：取消无法中断阻塞调用，结果在非取消上下文算出，
                // 返回点（外层 job 已取消）立即以 CancellationException 收场
                val result = withContext(kotlinx.coroutines.NonCancellable) {
                    pending.release.await()
                    EndpointTestResult(
                        apiLatencyMs = 42,
                        mediaFirstByteMs = null,
                        mediaThroughputMbps = null,
                        httpCode = 200,
                        protocol = baseUrl.substringBefore(":"),
                        supportsRange = true,
                    )
                }
                return result
            }
            pending.release.await()
            return EndpointTestResult(
                apiLatencyMs = 42,
                mediaFirstByteMs = null,
                mediaThroughputMbps = null,
                httpCode = 200,
                protocol = baseUrl.substringBefore(":"),
                supportsRange = true,
            )
        }
    }

    data class QualityUpdate(
        val serverId: String,
        val endpointId: String,
        val expectedUrl: String,
        val apiLatencyMs: Long?,
        val mediaFirstByteMs: Long?,
        val throughputMbps: Double?,
        val protocol: String?,
        val supportsRange: Boolean?,
        val httpCode: Int?,
    )

    class FakeServerStore(initial: List<MediaServer>) : ServerStore {
        val servers = initial.toMutableList()
        val updated = mutableListOf<MediaServer>()
        val qualityUpdates = mutableListOf<QualityUpdate>()

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
        ) {
            qualityUpdates += QualityUpdate(
                serverId, endpointId, expectedUrl,
                apiLatencyMs, mediaFirstByteMs, throughputMbps, protocol, supportsRange, httpCode,
            )
        }

        override suspend fun setDefault(id: String) { servers.replaceAll { it.copy(isDefault = it.id == id) } }
    }

    private class FakeRemoveHandler : ServerRemoveHandler {
        var removed: MediaServer? = null
        override suspend fun remove(server: MediaServer) { removed = server }
    }

    private val embyFactory = object : MediaProviderFactory {
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
            throw UnsupportedOperationException("编辑器测试不触达 Provider 实例")
    }

    private val registry = object : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): MediaProviderFactory? =
            if (type == ServerType.EMBY) embyFactory else null

        override fun create(server: MediaServer): ProviderHandle =
            throw UnsupportedOperationException("编辑器测试不触达 Provider 实例")

        override val supportedTypes = setOf(ServerType.EMBY)
        override fun descriptors() = listOf(embyFactory.descriptor)
    }

    private fun localServer() = MediaServer(
        id = "srv-local",
        name = "本地存储",
        type = ServerType.LOCAL,
        createdAtEpochMs = 0,
    )

    private fun embyServer() = MediaServer(
        id = "srv-net",
        name = "Emby",
        type = ServerType.EMBY,
        endpoints = listOf(
            ServerEndpoint(
                id = "ep-1",
                serverId = "srv-net",
                name = "默认线路",
                url = SAVED_URL,
                isPrimary = true,
                enabled = true,
                sortOrder = 0,
            )
        ),
        createdAtEpochMs = 0,
    )

    private lateinit var store: FakeServerStore
    private lateinit var service: FakeEndpointTestService
    private lateinit var removeHandler: FakeRemoveHandler

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = FakeServerStore(listOf(localServer(), embyServer()))
        service = FakeEndpointTestService()
        removeHandler = FakeRemoveHandler()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(serverId: String): ServerEditorViewModel = ServerEditorViewModel(
        savedStateHandle = SavedStateHandle(mapOf("serverId" to serverId)),
        serverStore = store,
        registry = registry,
        tokenStore = TokenStore(FakeSecretStorage()),
        serverIconStore = ServerIconStore(RuntimeEnvironment.getApplication()),
        removeHandler = removeHandler,
        endpointTestService = service,
        logger = StdoutLogger(),
    )

    // ---- P1：LOCAL 媒体源元数据编辑回归 ----

    @Test
    fun `local server rename saves metadata and preserves endpoints without address`() = runTest {
        val vm = viewModel("srv-local")
        advanceUntilIdle()

        assertNull("LOCAL 不参与网络地址校验", vm.uiState.value.addressError)
        vm.updateName("改名后的本地源")
        vm.save { }
        advanceUntilIdle()

        val saved = store.updated.single()
        assertEquals("改名后的本地源", saved.name)
        assertEquals("本地目录/路径属于设备环境，必须原样保留", localServer().endpoints, saved.endpoints)
        assertTrue("LOCAL 无网络地址，不得伪造", saved.endpoints.isEmpty())
        assertNull(vm.uiState.value.addressError)
    }

    // ---- P2：线路质量测试双闸 ----

    @Test
    fun qualityTestDoesNotApplyStaleResult() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        assertEquals(1, service.calls)

        // 测试在途：地址 A 改为 B（草稿版本递增 + 取消在途任务）
        vm.updateBaseUrl("https://media-b.example")
        service.releaseLast()
        advanceUntilIdle()

        assertNull("A 的在途结果不得显示在 B 草稿下", vm.uiState.value.mediaQualityResult)
        assertTrue("A 的在途结果不得写库", store.qualityUpdates.isEmpty())
        assertTrue("取消后 loading 必须复位", !vm.uiState.value.isMediaTesting)
    }

    @Test
    fun `quality test discarded when https toggled mid test`() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()

        vm.toggleHttps(false)
        service.releaseLast()
        advanceUntilIdle()

        assertNull(vm.uiState.value.mediaQualityResult)
        assertTrue(store.qualityUpdates.isEmpty())
        assertTrue("取消后 loading 必须复位", !vm.uiState.value.isMediaTesting)
    }

    @Test
    fun `unsaved draft quality result displays but does not overwrite saved endpoint`() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        // 输入未保存草稿 B（无后续编辑），测试 B
        service.enqueueTest()
        vm.updateBaseUrl("https://media-b.example")
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        service.releaseLast()
        advanceUntilIdle()

        assertEquals("结果可以展示在草稿下", "https://media-b.example", vm.uiState.value.resolvedUrl)
        assertTrue(vm.uiState.value.mediaQualityResult != null)
        assertTrue("未保存草稿的质量结果不得写库（不覆盖已保存 A）", store.qualityUpdates.isEmpty())
    }

    @Test
    fun `quality test on saved address persists result with endpoint identity`() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        service.releaseLast()
        advanceUntilIdle()

        val update = store.qualityUpdates.single()
        assertEquals("srv-net", update.serverId)
        assertEquals("ep-1", update.endpointId)
        assertEquals(SAVED_URL, update.expectedUrl)
        assertNotNull(vm.uiState.value.mediaQualityResult)
    }

    @Test
    fun `cancelling quality test resets loading and allows new test`() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        assertTrue(vm.uiState.value.isMediaTesting)

        // 取消在途任务（地址变化）
        vm.updateBaseUrl("https://media-c.example")
        runCurrent()
        assertTrue("取消收尾必须复位 loading", !vm.uiState.value.isMediaTesting)

        // 取消后必须能发起新测试并完成
        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        assertTrue(vm.uiState.value.isMediaTesting)
        service.releaseLast()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.mediaQualityResult)
        assertTrue(!vm.uiState.value.isMediaTesting)
    }

    @Test
    fun `late result after cancellation is neither displayed nor persisted`() = runTest {
        val vm = viewModel("srv-net")
        advanceUntilIdle()

        // 模拟阻塞 IO：取消不能中断，旧结果在取消后才"返回"
        service.ignoreCancellation = true
        service.enqueueTest()
        vm.testMediaQuality()
        runCurrent()
        service.awaitEntered()
        assertTrue(vm.uiState.value.isMediaTesting)

        vm.updateBaseUrl("https://media-b.example") // 取消任务
        runCurrent()

        assertTrue(vm.uiState.value.mediaQualityResult == null)
        assertTrue(store.qualityUpdates.isEmpty())
    }

    private companion object {
        const val SAVED_URL = "https://media.example"
    }
}
