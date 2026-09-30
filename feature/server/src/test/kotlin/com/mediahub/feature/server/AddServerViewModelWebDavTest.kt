package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
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
 * AddServerViewModel × WebDAV 表单链路（Z3 正式闭环的调用方契约）：
 *
 * - **凭据类型契约**：WebDAV 表单走统一 `loginAndSave`，必须以
 *   [Credentials.UsernamePassword] 调用 Provider 认证（Provider 侧同时接受
 *   Credentials.WebDav，但 UI 只构造前者——不能只在测试里手工构造另一种）。
 * - 密码只进认证请求与凭据库，**绝不写入 MediaServer**（Room 记录无密码字段）。
 * - 保存结果：type=WEBDAV + username 持久化 + 单条主线路 endpoint。
 * - 认证失败：可操作文案 + 不落库。
 *
 * 调度：真实主调度器 + 有界 latch——`ServerRepository.addServer` 的 Room suspend DAO
 * 会跳真实执行器线程，虚拟时间 advanceUntilIdle 不等真实 IO（项目既定教训）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerViewModelWebDavTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "addserver-webdav-main").apply { isDaemon = true }
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    private class RecordingAuth : MediaAuthProvider {
        val received = mutableListOf<Credentials>()
        var nextResult: AuthResult = AuthResult.Success(
            MediaUser(serverId = "srv-x", userId = "alice", displayName = "alice"),
        )
        var logoutCount = 0

        override suspend fun authenticate(credentials: Credentials): AuthResult {
            received += credentials
            return nextResult
        }
        override suspend fun refreshSession() = error("未用")
        override suspend fun restoreSession() = error("未用")
        override suspend fun logout() { logoutCount++ }
        override suspend fun currentUser(): com.mediahub.model.MediaUser? = null
    }

    private val webdavDescriptor = ProviderDescriptor(
        id = "webdav",
        serverType = ServerType.WEBDAV,
        displayName = "WebDAV",
        category = ProviderCategory.CLOUD_STORAGE,
        declaredCapabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.BASIC,
        status = ProviderStatus.EXPERIMENTAL,
    )

    private class StubProvider(override val descriptor: ProviderDescriptor) : MediaProvider {
        override val serverId = "srv-x"
        override val type = descriptor.serverType
        override val displayName = descriptor.displayName
        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class StubFactory(private val auth: RecordingAuth) : MediaProviderFactory {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "webdav", serverType = ServerType.WEBDAV, displayName = "WebDAV",
            category = ProviderCategory.CLOUD_STORAGE,
            declaredCapabilities = setOf(ProviderCapability.AUTH),
            authMethod = AuthMethod.BASIC, status = ProviderStatus.EXPERIMENTAL,
        )
        override fun create(server: MediaServer): ProviderHandle =
            ProviderHandle(provider = StubProvider(descriptor), auth = auth)
    }

    private class StubRegistry(private val factory: StubFactory) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): MediaProviderFactory? =
            if (type == ServerType.WEBDAV) factory else null
        override fun create(server: MediaServer): ProviderHandle = factory.create(server)
        override val supportedTypes = setOf(ServerType.WEBDAV)
        override fun descriptors() = listOf(factory.descriptor)
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
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
        mainDispatcher.close()
    }

    private fun viewModel(auth: RecordingAuth): AddServerViewModel = AddServerViewModel(
        SavedStateHandle(emptyMap()),
        repository,
        StubRegistry(StubFactory(auth)),
        StdoutLogger(),
    )

    /** 有界等待落库回调（真实线程完成），不靠 sleep 伪造时序。 */
    private fun awaitSaved(latch: CountDownLatch): Boolean = latch.await(8, TimeUnit.SECONDS)

    /** 泵一次主队列：viewModelScope.launch 的同步段在真实主执行器上跑完。 */
    private fun pumpMain() {
        mainExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `webdav form authenticates with username password and saves single primary endpoint`() {
        val auth = RecordingAuth()
        val vm = viewModel(auth)
        mainExecutor.submit {
            vm.updateName("家里 NAS")
            vm.updateBaseUrl("http://192.168.1.5:5000")
            vm.updateUsername("alice")
            vm.updatePassword("p@ss word")
        }.get(5, TimeUnit.SECONDS)
        val resolvedUrl = requireNotNull(vm.uiState.value.resolvedUrl)

        var saved: MediaServer? = null
        val latch = CountDownLatch(1)
        mainExecutor.submit { vm.loginAndSave { saved = it; latch.countDown() } }.get(5, TimeUnit.SECONDS)
        assertTrue("登录+落库必须完成", awaitSaved(latch))

        assertEquals(1, auth.received.size)
        val credentials = auth.received.single()
        assertTrue(
            "UI 必须构造 UsernamePassword（Provider 侧兼容 WebDav 变体，但表单只走这一种）",
            credentials is Credentials.UsernamePassword,
        )
        assertEquals("alice", (credentials as Credentials.UsernamePassword).username)
        assertEquals("p@ss word", credentials.password)

        val server = requireNotNull(saved)
        assertEquals(ServerType.WEBDAV, server.type)
        assertEquals("alice", server.username)
        assertEquals("单条主线路", 1, server.endpoints.size)
        assertEquals(resolvedUrl, server.endpoints.single().url)
        assertTrue(server.endpoints.single().isPrimary)
        assertFalse("密码不得写入 MediaServer", server.toString().contains("p@ss word"))
        assertFalse(vm.uiState.value.isLoggingIn)
        assertEquals(null, vm.uiState.value.loginError)
        assertEquals(1, kotlinx.coroutines.runBlocking { repository.observeServers().first().size })
    }

    @Test
    fun `blank credentials are refused before any authentication`() {
        val auth = RecordingAuth()
        val vm = viewModel(auth)
        mainExecutor.submit { vm.updateBaseUrl("http://192.168.1.5:5000") }.get(5, TimeUnit.SECONDS)

        var savedCalled = false
        // 校验失败是同步路径：loginAndSave 在主执行器上直接返回，onSaved 不会被调用
        mainExecutor.submit { vm.loginAndSave { savedCalled = true } }.get(5, TimeUnit.SECONDS)

        assertEquals("请输入用户名和密码", vm.uiState.value.loginError)
        assertEquals(0, auth.received.size)
        assertFalse(savedCalled)
        assertTrue(kotlinx.coroutines.runBlocking { repository.observeServers().first().isEmpty() })
    }

    @Test
    fun `auth failure shows actionable text and does not persist`() {
        val auth = RecordingAuth().apply {
            nextResult = AuthResult.Failure(ProviderException.AuthFailed("srv-x"))
        }
        val vm = viewModel(auth)
        mainExecutor.submit {
            vm.updateBaseUrl("http://192.168.1.5:5000")
            vm.updateUsername("alice")
            vm.updatePassword("wrong")
        }.get(5, TimeUnit.SECONDS)

        var savedCalled = false
        // 认证失败在 launch 的同步段内完成（authenticate 无调度跳转），泵一次主队列即收敛
        mainExecutor.submit {
            vm.loginAndSave { savedCalled = true }
        }.get(5, TimeUnit.SECONDS)
        pumpMain()

        assertEquals("用户名或密码错误", vm.uiState.value.loginError)
        assertFalse(savedCalled)
        assertTrue(kotlinx.coroutines.runBlocking { repository.observeServers().first().isEmpty() })
        assertFalse(vm.uiState.value.isLoggingIn)
    }
}
