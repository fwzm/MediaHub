package com.mediahub.feature.home

import androidx.lifecycle.ViewModelStore
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel.forceRestore 竞态修复测试（FINAL PATCH 4）。
 * 覆盖：读 DB 最新 server（非缓存）、SessionExpired/SignedOut → Authenticated、非认证 Provider 不写状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAuth(
        var restoreResult: AuthSessionState = AuthSessionState.SignedOut,
        val recordedServerIds: MutableList<String> = mutableListOf(),
        var restoreBlock: (suspend () -> AuthSessionState)? = null,
    ) : MediaAuthProvider {
        override suspend fun authenticate(credentials: Credentials): AuthResult = AuthResult.Success(fakeUser())
        override suspend fun refreshSession(): AuthResult = AuthResult.Success(fakeUser())
        override suspend fun restoreSession(): AuthSessionState {
            recordedServerIds += "restored"
            return restoreBlock?.invoke() ?: restoreResult
        }
        override suspend fun logout() = Unit
        override suspend fun currentUser(): MediaUser? = null
        private fun fakeUser() = MediaUser("s1", "u1", "a")
    }

    private class FakeProvider(override val serverId: String) : MediaProvider {
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "emby",
            serverType = ServerType.EMBY,
            displayName = "Emby",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.AUTH),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeRegistry(
        var serverSeenByCreate: MutableList<MediaServer> = mutableListOf(),
        var auth: MediaAuthProvider? = null,
        var authForServer: ((MediaServer) -> MediaAuthProvider?)? = null,
    ) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle? {
            serverSeenByCreate += server
            return ProviderHandle(provider = FakeProvider(server.id), auth = authForServer?.invoke(server) ?: auth)
        }
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
    }

    private class FakeServerStore(
        var servers: List<MediaServer>,
        private val observedOverride: Flow<List<MediaServer>>? = null,
    ) : ServerStore {
        var latest: MediaServer? = null
        var getBlock: (suspend (String) -> MediaServer?)? = null
        private val observed = MutableStateFlow(servers)
        override fun observeServers(): Flow<List<MediaServer>> = observedOverride ?: observed
        fun emit(newServers: List<MediaServer>) {
            servers = newServers
            observed.value = newServers
        }
        override suspend fun getServer(id: String): MediaServer? =
            getBlock?.invoke(id) ?: latest ?: servers.firstOrNull { it.id == id }
        override suspend fun updateServer(server: MediaServer) { latest = server }
        override suspend fun setDefault(id: String) { }
        // updateEndpointQuality：继承接口抛错默认（只读 fake，本测试不触达质量写路径）
    }

    private class FakeProgressStore : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun emby(id: String, baseUrl: String) = MediaServer(
        id = id, name = "Emby", type = ServerType.EMBY, baseUrl = baseUrl, createdAtEpochMs = 1L,
    )

    // ---- A：forceRestore 用 DB 最新（getServer），非 servers 缓存 ----

    @Test
    fun `forceRestore ignores old database snapshot released after restored identity is observed`() = runTest(dispatcher) {
        assertOldDatabaseSnapshotCannotCreateHandle { it.forceRestore("srv-1") }
    }

    @Test
    fun `logout ignores old database snapshot released after restored identity is observed`() = runTest(dispatcher) {
        assertOldDatabaseSnapshotCannotCreateHandle { it.logout("srv-1") }
    }

    private suspend fun TestScope.assertOldDatabaseSnapshotCannotCreateHandle(action: (HomeViewModel) -> Unit) {
        val old = emby("srv-1", "https://old.example")
        val replacement = emby("srv-1", "https://restored.example")
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val oldAuth = FakeAuth(AuthSessionState.Authenticated(fakeUser()))
        val newAuth = FakeAuth(AuthSessionState.SignedOut)
        val store = FakeServerStore(listOf(old))
        val registry = FakeRegistry(authForServer = { if (it.baseUrl == old.baseUrl) oldAuth else newAuth })
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            assertTrue(vm.authStates.value[old.id] is AuthSessionState.Authenticated)
            store.getBlock = {
                val snapshot = old
                readStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRead.await()
                    snapshot
                }
            }
            action(vm)
            runCurrent()
            assertTrue("数据库已取得旧快照但尚未返回", readStarted.isCompleted)
            store.emit(listOf(replacement))
            runCurrent()
            assertEquals(AuthSessionState.SignedOut, vm.authStates.value[old.id])
            assertEquals(2, registry.serverSeenByCreate.size)

            releaseRead.complete(Unit)
            runCurrent()
            assertEquals("旧数据库返回不能新建可捕获新鉴权代际的旧地址handle", 2, registry.serverSeenByCreate.size)
            assertEquals(replacement, registry.serverSeenByCreate.last())
            assertEquals(AuthSessionState.SignedOut, vm.authStates.value[old.id])
            assertEquals("旧身份restore不能再执行", 1, oldAuth.recordedServerIds.size)
        } finally {
            releaseRead.complete(Unit)
            owner.clear()
            runCurrent()
        }
    }

    @Test
    fun `forceRestore can read database before initial server flow is ready`() = runTest(dispatcher) {
        val current = emby("srv-1", "https://current.example")
        val store = FakeServerStore(listOf(current), MutableSharedFlow())
        val auth = FakeAuth(AuthSessionState.Authenticated(fakeUser()))
        val registry = FakeRegistry(auth = auth)
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            assertTrue(vm.servers.value.isEmpty())
            vm.forceRestore(current.id)
            runCurrent()
            assertEquals(listOf(current), registry.serverSeenByCreate)
            assertTrue(vm.authStates.value[current.id] is AuthSessionState.Authenticated)
        } finally {
            owner.clear()
            runCurrent()
        }
    }

    @Test
    fun `database read cannot revive identity observed and removed while initial read was pending`() = runTest(dispatcher) {
        val old = emby("srv-1", "https://old.example")
        val replacement = emby("srv-1", "https://restored.example")
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val store = FakeServerStore(emptyList())
        store.getBlock = {
            readStarted.complete(Unit)
            withContext(NonCancellable) { releaseRead.await(); old }
        }
        val registry = FakeRegistry(auth = FakeAuth(AuthSessionState.SignedOut))
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            vm.forceRestore(old.id)
            runCurrent()
            assertTrue(readStarted.isCompleted)
            store.emit(listOf(replacement))
            runCurrent()
            assertEquals(listOf(replacement), registry.serverSeenByCreate)
            store.emit(emptyList())
            runCurrent()
            assertNull(vm.authStates.value[old.id])
            releaseRead.complete(Unit)
            runCurrent()
            assertEquals("删除后仍须保留读取代号，不能恢复旧地址handle", listOf(replacement), registry.serverSeenByCreate)
            assertNull(vm.authStates.value[old.id])
        } finally {
            releaseRead.complete(Unit)
            owner.clear()
            runCurrent()
        }
    }

    @Test
    fun `same id restored address account or provider change reloads cleared token state`() = runTest(dispatcher) {
        val old = emby("srv-1", "https://original.example/path").copy(username = "Alice")
        val replacements = listOf(
            old.copy(endpoints = old.endpoints.map { it.copy(url = "https://restored.example/path") }),
            old.copy(endpoints = old.endpoints.map { it.copy(url = "https://original.example:443/path") }),
            old.copy(username = "Bob"),
            old.copy(username = " Alice "),
            old.copy(type = ServerType.JELLYFIN),
        )
        for (replacement in replacements) {
            val store = FakeServerStore(listOf(old))
            var storedToken: String? = "task-only-old-token"
            val auth = FakeAuth(restoreBlock = {
                if (storedToken == null) AuthSessionState.SignedOut else AuthSessionState.Authenticated(fakeUser())
            })
            val registry = FakeRegistry(auth = auth)
            val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
            val owner = ViewModelStore().apply { put("home", vm) }
            try {
                runCurrent()
                assertTrue(vm.authStates.value[old.id] is AuthSessionState.Authenticated)
                storedToken = null // 恢复已先清持久登录态，然后才发布变化后的服务器。
                store.emit(listOf(replacement))
                runCurrent()

                assertEquals("同ID身份变化后必须读已清理登录态：$replacement", AuthSessionState.SignedOut, vm.authStates.value[old.id])
                assertEquals(2, auth.recordedServerIds.size)
                assertEquals(replacement, registry.serverSeenByCreate.last())
            } finally {
                owner.clear()
                runCurrent()
            }
        }
    }

    @Test
    fun `identity replacement is not blocked or overwritten by old noncancellable restore result`() = runTest(dispatcher) {
        val old = emby("srv-1", "https://old.example")
        val replacement = emby("srv-1", "https://restored.example")
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldAuth = FakeAuth(restoreBlock = {
            oldStarted.complete(Unit)
            withContext(NonCancellable) {
                releaseOld.await()
                AuthSessionState.Authenticated(fakeUser())
            }
        })
        val newAuth = FakeAuth(AuthSessionState.SignedOut)
        val store = FakeServerStore(listOf(old))
        val registry = FakeRegistry(authForServer = { if (it.baseUrl == old.baseUrl) oldAuth else newAuth })
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            assertTrue("旧restore已实际开始", oldStarted.isCompleted)
            store.emit(listOf(replacement))
            runCurrent()

            assertEquals("新身份不能等待旧请求放行", AuthSessionState.SignedOut, vm.authStates.value[old.id])
            assertEquals(1, newAuth.recordedServerIds.size)
            releaseOld.complete(Unit)
            runCurrent()
            assertEquals("旧认证成功的迟到结果必须丢弃", AuthSessionState.SignedOut, vm.authStates.value[old.id])
        } finally {
            releaseOld.complete(Unit)
            owner.clear()
            runCurrent()
        }
    }

    @Test
    fun `rename and equivalent normalized identity preserve existing authentication without restoring again`() = runTest(dispatcher) {
        val old = emby("srv-1", "https://host.example:443/path/").copy(username = "Alice")
        val renamed = old.copy(
            name = "Renamed task source", note = "Changed display metadata",
            endpoints = old.endpoints.map { it.copy(url = "HTTPS://HOST.EXAMPLE:443/path") },
        )
        val auth = FakeAuth(AuthSessionState.Authenticated(fakeUser()))
        val store = FakeServerStore(listOf(old))
        val vm = HomeViewModel(store, FakeProgressStore(), FakeRegistry(auth = auth), noOpLogger)
        val owner = ViewModelStore().apply { put("home", vm) }
        try {
            runCurrent()
            store.emit(listOf(renamed))
            runCurrent()

            assertTrue(vm.authStates.value[old.id] is AuthSessionState.Authenticated)
            assertEquals("同源重命名不全量重新登录", 1, auth.recordedServerIds.size)
        } finally {
            owner.clear()
            runCurrent()
        }
    }

    @Test
    fun `forceRestore reads latest server from db not stale cache`() = runTest {
        val old = emby("srv-1", "http://old-host")
        val updated = emby("srv-1", "http://new-host")
        val store = FakeServerStore(listOf(old))  // 缓存仍是 old
        store.latest = updated                        // DB 已更新为 new

        val registry = FakeRegistry(auth = FakeAuth(AuthSessionState.Authenticated(fakeUser())))
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        advanceUntilIdle()

        vm.forceRestore("srv-1")
        advanceUntilIdle()

        // registry.create 收到的必须是 new-host（DB 最新），不是 old-host 缓存
        assertEquals("http://new-host", registry.serverSeenByCreate.last().baseUrl)
    }

    // ---- B/C：SessionExpired / SignedOut → forceRestore → Authenticated ----

    @Test
    fun `forceRestore overwrites SessionExpired to Authenticated`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        // 初始 restore 结果 = SessionExpired（真实制造失效状态）
        val auth = FakeAuth(AuthSessionState.Error(AuthSessionErrorKind.SESSION_EXPIRED, "登录已失效"))
        val registry = FakeRegistry(auth = auth)
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        advanceUntilIdle()

        // 先断言确实进入 SessionExpired
        val before = vm.authStates.value["srv-1"]
        assertTrue("before=$before", before is AuthSessionState.Error)

        // 重新登录成功 → restore 返回 Authenticated
        auth.restoreResult = AuthSessionState.Authenticated(fakeUser())
        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertTrue("after=${vm.authStates.value["srv-1"]}", vm.authStates.value["srv-1"] is AuthSessionState.Authenticated)
    }

    @Test
    fun `forceRestore overwrites SignedOut to Authenticated`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        // 初始 restore 结果 = SignedOut（真实制造未登录状态）
        val auth = FakeAuth(AuthSessionState.SignedOut)
        val registry = FakeRegistry(auth = auth)
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        advanceUntilIdle()

        // 先断言确实 SignedOut
        assertEquals(AuthSessionState.SignedOut, vm.authStates.value["srv-1"])

        auth.restoreResult = AuthSessionState.Authenticated(fakeUser())
        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertTrue(vm.authStates.value["srv-1"] is AuthSessionState.Authenticated)
    }

    // ---- D：非认证 Provider forceRestore 不写 authStates ----

    @Test
    fun `forceRestore removes existing auth state when provider becomes non-auth`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        // 第一阶段：auth-capable Provider，restore → Authenticated
        val auth = FakeAuth(AuthSessionState.Authenticated(fakeUser()))
        val registry = FakeRegistry(auth = auth)
        val vm = HomeViewModel(store, FakeProgressStore(), registry, noOpLogger)
        advanceUntilIdle()

        // 先断言 authStates 确实包含该 serverId
        assertTrue(vm.authStates.value["srv-1"] is AuthSessionState.Authenticated)

        // 第二阶段：Provider 现在 auth == null（非认证）
        registry.auth = null
        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertNull(vm.authStates.value["srv-1"])
    }

    private fun fakeUser() = MediaUser("s1", "u1", "a")
}
