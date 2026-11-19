package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerViewModelAutoDetectTest {

    private val embyDescriptor = ProviderDescriptor(
        id = "emby",
        serverType = ServerType.EMBY,
        displayName = "Emby",
        category = ProviderCategory.MEDIA_SERVER,
        declaredCapabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.API_KEY,
        status = ProviderStatus.STABLE,
    )

    private class RecordingProvider(
        override val descriptor: ProviderDescriptor,
        private val succeedOnPort: Int?,
    ) : MediaProvider {
        val attemptedUrls = mutableListOf<String>()
        override val serverId = "srv-probe"
        override val type = descriptor.serverType
        override val displayName = descriptor.displayName
        private var currentUrl: String? = null

        override suspend fun testConnection(): ConnectionStatus {
            val url = requireNotNull(currentUrl)
            attemptedUrls += url
            return if (succeedOnPort != null && url.endsWith(":$succeedOnPort")) {
                ConnectionStatus(ok = true, latencyMs = 12)
            } else {
                ConnectionStatus(ok = false, message = "连接失败")
            }
        }

        fun record(server: MediaServer) { currentUrl = server.endpoints.single().url }
    }

    private inner class StubFactory(private val provider: RecordingProvider) : MediaProviderFactory {
        override val descriptor = embyDescriptor
        override fun create(server: MediaServer): ProviderHandle {
            provider.record(server)
            return ProviderHandle(provider = provider, auth = null)
        }
    }

    private inner class StubRegistry(private val factory: StubFactory) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): MediaProviderFactory? =
            if (type == ServerType.EMBY) factory else null
        override fun create(server: MediaServer): ProviderHandle = factory.create(server)
        override val supportedTypes = setOf(ServerType.EMBY)
        override fun descriptors() = listOf(factory.descriptor)
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ServerRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(provider: RecordingProvider): AddServerViewModel =
        AddServerViewModel(SavedStateHandle(emptyMap()), repository, StubRegistry(StubFactory(provider)), StdoutLogger())

    @Test
    fun `bare emby host test connection finds 8096 and adopts it`() {
        val provider = RecordingProvider(embyDescriptor, succeedOnPort = 8096)
        val vm = viewModel(provider)
        vm.updateBaseUrl("192.168.1.5")

        vm.testConnection()

        assertEquals(
            listOf(
                "https://192.168.1.5",
                "https://192.168.1.5:8920",
                "http://192.168.1.5:8096",
            ),
            provider.attemptedUrls,
        )
        val state = vm.uiState.value
        assertEquals("http://192.168.1.5:8096", state.baseUrl)
        assertEquals("http://192.168.1.5:8096", state.resolvedUrl)
        assertFalse("探测结束后必须退出测试中态", state.isTesting)
        assertTrue(requireNotNull(state.testResult).ok)
        assertTrue(requireNotNull(state.testResult).message.orEmpty().contains("8096"))
    }

    @Test
    fun `explicit port is tested once and never rewritten`() {
        val provider = RecordingProvider(embyDescriptor, succeedOnPort = null)
        val vm = viewModel(provider)
        vm.updateBaseUrl("http://192.168.1.5:5000")

        vm.testConnection()

        assertEquals(listOf("http://192.168.1.5:5000"), provider.attemptedUrls)
        assertEquals("http://192.168.1.5:5000", vm.uiState.value.baseUrl)
        assertNotNull(vm.uiState.value.testResult)
        assertFalse(requireNotNull(vm.uiState.value.testResult).ok)
    }
}
