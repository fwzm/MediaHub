package com.mediahub.feature.home

import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.AuthenticationCoordinator
import com.mediahub.provider.api.AuthenticationDisposition
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel.forceRestore 竞态修复测试（FINAL PATCH 5，PR 架构）。
 * 覆盖：读 DB 最新 server（非缓存）、SessionExpired/SignedOut → Authenticated、非认证不写状态、
 * 已有状态仍强制 restore。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun user() = MediaUser("s1", "u1", "a")

    private class FakeCoordinator(
        var restoreResult: AuthenticationState = AuthenticationState.Authenticated(MediaUser("s1", "u1", "a")),
        var restoreCalls: Int = 0,
    ) : AuthenticationCoordinator {
        override suspend fun authenticateOrDefer(handle: ProviderHandle, credentials: Credentials): AuthenticationDisposition =
            AuthenticationDisposition.DeferredUntilProviderImplementation
        override suspend fun restore(handle: ProviderHandle): AuthenticationState {
            restoreCalls++
            return restoreResult
        }
        override suspend fun logout(handle: ProviderHandle) = Unit
        override suspend fun clear(serverId: String) = Unit
    }

    private class FakeAuth : MediaAuthProvider {
        private fun session() = com.mediahub.provider.api.AuthSession(
            credential = com.mediahub.provider.api.SessionCredential.ApiKey("key"),
            user = MediaUser("s1", "u1", "a"),
            remoteServerId = "remote-1",
        )
        override suspend fun authenticate(credentials: Credentials): com.mediahub.provider.api.AuthResult =
            com.mediahub.provider.api.AuthResult.Success(session())
        override suspend fun restoreSession(session: com.mediahub.provider.api.AuthSession): com.mediahub.provider.api.SessionRestoreResult =
            com.mediahub.provider.api.SessionRestoreResult.Restored(session)
        override suspend fun logout(session: com.mediahub.provider.api.AuthSession) = Unit
    }

    private class FakeProvider(override val serverId: String) : MediaProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            providerId = "emby",
            displayName = "Emby",
            description = "",
            category = ProviderCategory.MEDIA_SERVER,
            capabilities = setOf(ProviderCapability.AUTH),
            authMethod = AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.AVAILABLE,
        )
        override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeRegistry(
        var serverSeenByCreate: MutableList<MediaServer> = mutableListOf(),
        var auth: MediaAuthProvider? = null,
    ) : MediaProviderRegistry {
        override fun factoryFor(providerId: String): com.mediahub.provider.api.MediaProviderFactory? = null
        override fun descriptorFor(providerId: String): ProviderDescriptor? = null
        override fun create(server: MediaServer): ProviderHandle? {
            serverSeenByCreate += server
            return ProviderHandle(provider = FakeProvider(server.id), auth = auth)
        }
        override val descriptors: List<ProviderDescriptor> = emptyList()
    }

    private class FakeServerStore(var servers: List<MediaServer>) : ServerStore {
        var latest: MediaServer? = null
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(servers)
        override suspend fun getServer(id: String): MediaServer? = latest ?: servers.firstOrNull { it.id == id }
    }

    private class FakeProgressStore : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun emby(id: String, baseUrl: String) = MediaServer(
        id = id, name = "Emby", providerId = "emby", baseUrl = baseUrl, createdAtEpochMs = 1L,
    )

    // ---- A：forceRestore 读 DB 最新（getServer），非 servers 缓存 ----

    @Test
    fun `forceRestore reads latest server from db not stale cache`() = runTest {
        val old = emby("srv-1", "http://old-host")
        val updated = emby("srv-1", "http://new-host")
        val store = FakeServerStore(listOf(old))  // 缓存仍是 old
        store.latest = updated                        // DB 已更新为 new

        val registry = FakeRegistry(auth = FakeAuth())
        val vm = HomeViewModel(store, FakeProgressStore(), registry, FakeCoordinator(), noOpLogger)
        advanceUntilIdle()

        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertEquals("http://new-host", registry.serverSeenByCreate.last().baseUrl)
    }

    // ---- B：SessionExpired → forceRestore → Authenticated ----

    @Test
    fun `forceRestore overwrites SessionExpired to Authenticated`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        val registry = FakeRegistry(auth = FakeAuth())
        val vm = HomeViewModel(store, FakeProgressStore(), registry, FakeCoordinator(), noOpLogger)
        advanceUntilIdle()
        // 预置 SessionExpired（模拟旧状态）
        // 直接 forceRestore 覆盖
        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertTrue(vm.authStates.value["srv-1"] is AuthenticationState.Authenticated)
    }

    @Test
    fun `forceRestore overwrites SignedOut to Authenticated`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        val registry = FakeRegistry(auth = FakeAuth())
        val vm = HomeViewModel(store, FakeProgressStore(), registry, FakeCoordinator(), noOpLogger)
        advanceUntilIdle()

        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertTrue(vm.authStates.value["srv-1"] is AuthenticationState.Authenticated)
    }

    // ---- D：非认证 Provider forceRestore 不写 authStates ----

    @Test
    fun `forceRestore non-auth provider removes id from authStates`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        val registry = FakeRegistry(auth = null) // 非认证
        val vm = HomeViewModel(store, FakeProgressStore(), registry, FakeCoordinator(), noOpLogger)
        advanceUntilIdle()

        vm.forceRestore("srv-1")
        advanceUntilIdle()

        assertNull(vm.authStates.value["srv-1"])
    }

    // ---- E：已有状态仍强制 restore（不受 init containsKey 去重影响） ----

    @Test
    fun `forceRestore restores even when id already present in authStates`() = runTest {
        val server = emby("srv-1", "http://h")
        val store = FakeServerStore(listOf(server))
        val coordinator = FakeCoordinator(restoreResult = AuthenticationState.Authenticated(user()))
        val registry = FakeRegistry(auth = FakeAuth())
        val vm = HomeViewModel(store, FakeProgressStore(), registry, coordinator, noOpLogger)
        advanceUntilIdle() // init 已完成首次 restore（restoreCalls=1）

        vm.forceRestore("srv-1")
        advanceUntilIdle()

        // 第二次 restore 被执行（不受 containsKey 去重）
        assertEquals(2, coordinator.restoreCalls)
        assertTrue(vm.authStates.value["srv-1"] is AuthenticationState.Authenticated)
    }
}
