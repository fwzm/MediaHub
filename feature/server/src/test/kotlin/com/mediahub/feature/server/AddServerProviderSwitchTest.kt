package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.model.ServerType
import com.mediahub.provider.api.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerProviderSwitchTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "provider-switch-main").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val store = ViewModelStore()
    private val models = mutableListOf<AddServerViewModel>()
    private val emby = descriptor("emby", ServerType.EMBY, AuthMethod.USERNAME_PASSWORD)
    private val dav = descriptor("webdav", ServerType.WEBDAV, AuthMethod.BASIC)
    private val local = descriptor("local", ServerType.LOCAL, AuthMethod.NONE)
    private val auths = mapOf(ServerType.EMBY to TestAuth(), ServerType.WEBDAV to TestAuth())
    private val probes = ServerType.entries.associateWith { TestProbe() }
    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository

    private fun descriptor(id: String, type: ServerType, method: AuthMethod) = ProviderDescriptor(
        id = id, serverType = type, displayName = "Fixture $id", category = ProviderCategory.MEDIA_SERVER,
        declaredCapabilities = if (method == AuthMethod.NONE) emptySet() else setOf(ProviderCapability.AUTH),
        authMethod = method, status = ProviderStatus.STABLE,
    )

    private class TestAuth : MediaAuthProvider {
        val received = mutableListOf<Credentials>()
        var gate: CompletableDeferred<AuthResult>? = null
        var serverId = ""
        var logoutCalls = 0
        override suspend fun authenticate(credentials: Credentials): AuthResult {
            received += credentials
            return gate?.await() ?: success()
        }
        fun success() = AuthResult.Success(MediaUser(serverId, "fixture-user", "Fixture"))
        override suspend fun refreshSession(): AuthResult = error("unused")
        override suspend fun restoreSession(): AuthSessionState = error("unused")
        override suspend fun currentUser(): MediaUser? = null
        override suspend fun logout() { logoutCalls++ }
    }

    private class TestProbe {
        var gate: CompletableDeferred<ConnectionStatus>? = null
        suspend fun run(): ConnectionStatus = gate?.let {
            // An old provider may finish after cancellation; its owner must still be checked.
            withContext(NonCancellable) { it.await() }
        } ?: ConnectionStatus(true, message = "fixture probe")
    }

    private val registry = object : MediaProviderRegistry {
        override val supportedTypes = setOf(ServerType.EMBY, ServerType.WEBDAV, ServerType.LOCAL)
        override fun descriptors() = listOf(emby, dav, local)
        override fun factoryFor(type: ServerType): MediaProviderFactory? {
            val metadata = descriptors().firstOrNull { it.serverType == type } ?: return null
            return object : MediaProviderFactory {
                override val descriptor = metadata
                override fun create(server: MediaServer): ProviderHandle {
                    val provider = object : MediaProvider {
                        override val descriptor = metadata
                        override val serverId = server.id
                        override val type = server.type
                        override val displayName = server.name
                        override suspend fun testConnection() = probes.getValue(server.type).run()
                    }
                    val auth = auths[server.type]?.also { it.serverId = server.id }
                    return ProviderHandle(provider = provider, auth = auth)
                }
            }
        }
        override fun create(server: MediaServer) = factoryFor(server.type)?.create(server)
    }

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ServerRepository(db)
    }

    @After
    fun after() {
        probes.values.forEach { it.gate?.complete(ConnectionStatus(false, message = "fixture cleanup")) }
        auths.values.forEach { it.gate?.complete(AuthResult.Failure(ProviderException.AuthFailed("fixture"))) }
        val jobs = main {
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            store.clear()
            jobs
        }
        runBlocking { withTimeout(8_000) { jobs.forEach { it.join() } } }
        db.close()
        Dispatchers.resetMain()
        dispatcher.close()
    }

    private fun <T> main(block: () -> T): T = try {
        executor.submit<T> { block() }.get(8, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw (error.cause ?: error)
    }

    private fun model(reauthorizeId: String = ""): AddServerViewModel = main {
        AddServerViewModel(SavedStateHandle(mapOf("reauthorizeId" to reauthorizeId)), repository, registry, StdoutLogger())
            .also { models += it; store.put("fixture-${models.size}", it) }
    }

    private fun fill(vm: AddServerViewModel) = main {
        vm.updateName("Fixture source")
        vm.updateBaseUrl("http://fixture.invalid:8096/proxy")
        vm.updateUsername("fixture-account-a")
        vm.updatePassword("fixture-password-a")
    }

    private fun awaitState(vm: AddServerViewModel, predicate: (AddServerUiState) -> Boolean) =
        runBlocking { withTimeout(8_000) { vm.uiState.first(predicate) } }

    private fun await(latch: CountDownLatch) = assertTrue("Owned operation must finish", latch.await(8, TimeUnit.SECONDS))

    @Test
    fun changingProviderClearsItsCredentialsAddressAndDerivedState() {
        val vm = model(); fill(vm)
        val version = vm.uiState.value.addressVersion
        main { vm.selectProvider(dav) }
        val state = vm.uiState.value
        assertEquals("webdav", state.selectedDescriptorId)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals("", state.baseUrl)
        assertNull(state.resolvedUrl)
        assertNull(state.addressError)
        assertTrue(state.preferHttps)
        assertTrue(state.addressVersion > version)
        assertEquals(dav.displayName, state.name)
        assertNull(state.testResult)
        assertFalse(state.isTesting)
    }

    @Test
    fun selectingCurrentProviderKeepsTheDraftAndCompletedProbe() {
        val vm = model(); fill(vm)
        main { vm.testConnection() }
        awaitState(vm) { it.testResult != null && !it.isTesting }
        val before = vm.uiState.value
        main { vm.selectProvider(emby) }
        assertEquals(before, vm.uiState.value)
    }

    @Test
    fun reauthorizationLocksProviderBeforeTheDatabaseReadCompletes() {
        main {
            val vm = AddServerViewModel(SavedStateHandle(mapOf("reauthorizeId" to "fixture-existing")), repository, registry, StdoutLogger())
            models += vm; store.put("loading-existing", vm)
            vm.selectProvider(dav)
            assertEquals("emby", vm.uiState.value.selectedDescriptorId)
        }
    }

    @Test
    fun loadedExistingProviderAndEnteredCredentialsRemainLocked() {
        val existing = runBlocking {
            repository.addServer(MediaServer(id = "fixture-existing", name = "Existing", type = ServerType.EMBY,
                baseUrl = "http://fixture.invalid:8096", username = "fixture-existing-account", createdAtEpochMs = 0))
        }
        val vm = model(existing.id)
        awaitState(vm) { !it.isLoadingExisting }
        main { vm.updatePassword("fixture-existing-password") }
        val before = vm.uiState.value
        main { vm.selectProvider(dav) }
        assertEquals(before, vm.uiState.value)
        assertEquals(1, runBlocking { repository.observeServers().first().size })
    }

    @Test
    fun pendingLoginOwnsTheProviderAndInputsUntilItsSaveCompletes() {
        val vm = model(); fill(vm)
        val auth = auths.getValue(ServerType.EMBY)
        auth.gate = CompletableDeferred()
        val saved = CountDownLatch(1)
        var result: MediaServer? = null
        main {
            vm.loginAndSave { result = it; saved.countDown() }
            assertTrue(vm.uiState.value.isLoggingIn)
            val before = vm.uiState.value
            vm.selectProvider(dav)
            vm.updateName("replacement")
            vm.updateBaseUrl("https://replacement.invalid")
            vm.toggleHttps(true)
            vm.updateUsername("replacement-account")
            vm.updatePassword("replacement-password")
            assertEquals(before, vm.uiState.value)
        }
        main {} // Run the admitted coroutine to its authentication suspension.
        auth.gate!!.complete(auth.success())
        await(saved)
        main {
            assertFalse(vm.uiState.value.isLoggingIn)
            assertEquals("", vm.uiState.value.password)
            assertEquals(ServerType.EMBY, result?.type)
            assertEquals("fixture-account-a", result?.username)
        }
    }

    @Test
    fun duplicateLoginBeforeCoroutineDispatchAuthenticatesOnlyOnce() {
        val vm = model(); fill(vm)
        val auth = auths.getValue(ServerType.EMBY)
        auth.gate = CompletableDeferred()
        main {
            vm.loginAndSave { error("Deferred login has not succeeded") }
            vm.loginAndSave { error("Duplicate login must not be admitted") }
        }
        main { assertEquals(1, auth.received.size) }
    }

    @Test
    fun authenticationFailureUnlocksSwitchingAndTheSwitchDiscardsSecrets() {
        val vm = model(); fill(vm)
        val auth = auths.getValue(ServerType.EMBY)
        auth.gate = CompletableDeferred()
        main { vm.loginAndSave { error("Failure must not persist") } }
        main {}
        auth.gate!!.complete(AuthResult.Failure(ProviderException.AuthFailed("fixture")))
        awaitState(vm) { !it.isLoggingIn && it.loginError != null }
        main { vm.selectProvider(dav) }
        assertEquals("webdav", vm.uiState.value.selectedDescriptorId)
        assertEquals("", vm.uiState.value.username)
        assertEquals("", vm.uiState.value.password)
        assertTrue(runBlocking { repository.observeServers().first().isEmpty() })
    }

    @Test
    fun pendingSaveLocksProviderSynchronouslyAndReleasesItAfterCallback() {
        val vm = model()
        main { vm.selectProvider(local); vm.updateName("Fixture local") }
        val saved = CountDownLatch(1)
        var result: MediaServer? = null
        main {
            vm.save { result = it; saved.countDown() }
            vm.selectProvider(dav)
            assertEquals("local", vm.uiState.value.selectedDescriptorId)
            assertTrue(vm.uiState.value.isSaving)
        }
        await(saved)
        main {
            assertFalse(vm.uiState.value.isSaving)
            assertEquals(ServerType.LOCAL, result?.type)
            vm.selectProvider(dav)
            assertEquals("webdav", vm.uiState.value.selectedDescriptorId)
        }
    }

    @Test
    fun anOldProbeCannotClearTheNewProbeLoadingStateOrPublishItsResult() {
        val vm = model(); fill(vm)
        val a = CompletableDeferred<ConnectionStatus>(); probes.getValue(ServerType.EMBY).gate = a
        val b = CompletableDeferred<ConnectionStatus>(); probes.getValue(ServerType.WEBDAV).gate = b
        main { vm.testConnection() }
        val oldJob = main { vm.viewModelScope.coroutineContext[Job]!!.children.single() }
        val oldFinished = CountDownLatch(1); oldJob.invokeOnCompletion { oldFinished.countDown() }
        main {
            vm.selectProvider(dav)
            vm.updateBaseUrl("https://fixture-b.invalid/dav")
            vm.testConnection()
        }
        main {}
        a.complete(ConnectionStatus(true, message = "fixture old provider"))
        await(oldFinished)
        main {
            assertEquals("webdav", vm.uiState.value.selectedDescriptorId)
            assertTrue("Old cleanup must not release the current probe", vm.uiState.value.isTesting)
            assertNull(vm.uiState.value.testResult)
        }
        b.complete(ConnectionStatus(true, message = "fixture new provider"))
        val state = awaitState(vm) { !it.isTesting && it.testResult != null }
        assertEquals("fixture new provider", state.testResult?.message)
    }

    @Test
    fun cancelledAuthenticationDoesNotBecomeALoginFailureOrPersist() {
        val vm = model(); fill(vm)
        val auth = auths.getValue(ServerType.EMBY)
        auth.gate = CompletableDeferred()
        main { vm.loginAndSave { error("Cancelled login must not persist") } }
        val operation = main {
            assertEquals(1, auth.received.size)
            vm.viewModelScope.coroutineContext[Job]!!.children.single()
        }
        val cancelled = CancellationException("fixture login cancellation")
        val finished = CountDownLatch(1)
        var cause: Throwable? = null
        operation.invokeOnCompletion { cause = it; finished.countDown() }
        main { operation.cancel(cancelled) }
        await(finished)
        main {
            assertSame(cancelled, cause)
            assertFalse(vm.uiState.value.isLoggingIn)
            assertNull(vm.uiState.value.loginError)
            assertEquals(0, auth.logoutCalls)
        }
        assertTrue(runBlocking { repository.observeServers().first().isEmpty() })
    }

    @Test
    fun anUnregisteredProviderCannotReplaceTheCurrentDraft() {
        val vm = model(); fill(vm)
        val before = vm.uiState.value
        main { vm.selectProvider(dav.copy(id = "fixture-unregistered")) }
        assertEquals(before, vm.uiState.value)
    }
}
