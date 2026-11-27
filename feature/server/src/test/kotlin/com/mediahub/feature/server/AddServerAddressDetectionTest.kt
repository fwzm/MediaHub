package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「添加服务器 → 自动识别协议/端口」（审计条目 75）。
 *
 * 探测本身由内部钩子注入假 prober，**不发任何真实网络请求**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerAddressDetectionTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository

    private val emby = ProviderDescriptor(
        id = "emby",
        serverType = ServerType.EMBY,
        displayName = "Emby",
        category = ProviderCategory.MEDIA_SERVER,
        declaredCapabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.USERNAME_PASSWORD,
        status = ProviderStatus.STABLE,
    )
    private val local = ProviderDescriptor(
        id = "local",
        serverType = ServerType.LOCAL,
        displayName = "本地目录",
        category = ProviderCategory.CLOUD_STORAGE,
        declaredCapabilities = emptySet(),
        authMethod = AuthMethod.NONE,
        status = ProviderStatus.STABLE,
    )

    private val registry = object : MediaProviderRegistry {
        override val supportedTypes = setOf(ServerType.EMBY, ServerType.LOCAL)
        override fun descriptors(): List<ProviderDescriptor> = listOf(emby, local)
        override fun create(server: MediaServer): ProviderHandle? = null
        override fun factoryFor(type: ServerType) = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ServerRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = AddServerViewModel(
        SavedStateHandle(emptyMap()),
        repository,
        registry,
        StdoutLogger(),
    )

    @Test
    fun `detect applies the first reachable candidate to the form`() = runTest(dispatcher) {
        val vm = viewModel()
        val probed = mutableListOf<String>()
        vm.addressProber = { url ->
            probed += url
            url == "https://media.example:8920"
        }
        vm.updateBaseUrl("media.example")
        val versionBefore = vm.uiState.value.addressVersion

        vm.detectAddress()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("https://media.example:8920", state.baseUrl)
        assertEquals("https://media.example:8920", state.resolvedUrl)
        assertEquals("https://media.example:8920", state.detectedAddress)
        assertTrue("成功识别必须同步 HTTPS 开关", state.preferHttps)
        assertNull(state.addressError)
        assertFalse(state.isDetecting)
        assertTrue("地址版本必须递增，避免过期结果回写", state.addressVersion > versionBefore)
        assertEquals(listOf("https://media.example:8920"), probed)
    }

    @Test
    fun `detect failure keeps input and lists attempted candidates`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addressProber = { false }
        vm.updateBaseUrl("media.example")

        vm.detectAddress()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("media.example", state.baseUrl)
        assertNull(state.detectedAddress)
        assertFalse(state.isDetecting)
        val message = requireNotNull(state.detectionMessage)
        assertTrue("必须给出可读失败文案（实际：$message）", message.contains("未能自动识别"))
        assertTrue("必须列出已尝试的候选（实际：$message）", message.contains("http://media.example:8096"))
        assertTrue("必须列出 https 候选（实际：$message）", message.contains("https://media.example:8920"))
    }

    @Test
    fun `changing the address discards a stale detection result`() = runTest(dispatcher) {
        val vm = viewModel()
        val gate = CompletableDeferred<Boolean>()
        vm.addressProber = { gate.await() }
        vm.updateBaseUrl("media.example")

        vm.detectAddress()
        advanceUntilIdle() // 协程挂在假 prober 上

        vm.updateBaseUrl("other.example")
        assertFalse(vm.uiState.value.isDetecting)
        assertNull(vm.uiState.value.detectionMessage)

        gate.complete(true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("过期识别结果不得回写", state.detectedAddress)
        assertEquals("other.example", state.baseUrl)
    }

    @Test
    fun `invalid address is rejected without probing`() = runTest(dispatcher) {
        val vm = viewModel()
        var probes = 0
        vm.addressProber = { probes++; true }
        vm.updateBaseUrl("ftp://media.example")

        vm.detectAddress()
        advanceUntilIdle()

        assertEquals(0, probes)
        assertNull(vm.uiState.value.detectedAddress)
        assertTrue(vm.uiState.value.addressError?.contains("不支持的协议") == true)
    }

    @Test
    fun `local source has no protocol detection`() = runTest(dispatcher) {
        val vm = viewModel()
        var probes = 0
        vm.addressProber = { probes++; true }
        vm.selectProvider(local)
        vm.updateBaseUrl("content://com.android.externalstorage.documents/tree/primary%3AMovies")

        vm.detectAddress()
        advanceUntilIdle()

        assertEquals("本地目录不得触发网络探测", 0, probes)
        assertNull(vm.uiState.value.detectedAddress)
        assertNull(vm.uiState.value.detectionMessage)
        assertFalse(vm.uiState.value.isDetecting)
    }
}
