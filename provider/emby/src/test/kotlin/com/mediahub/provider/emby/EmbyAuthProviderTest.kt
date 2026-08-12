package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.AuthenticationDisposition
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.base.DefaultAuthenticationCoordinator
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.session.EmbySessionValidator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbyAuthProviderTest {
    private lateinit var mock: MockWebServer

    private val mediaServer: MediaServer
        get() = MediaServer(
            id = "server-1",
            name = "Emby",
            providerId = "emby",
            baseUrl = mock.url("/emby").toString().trimEnd('/'),
            createdAtEpochMs = 0,
        )

    @Before fun setUp() { mock = MockWebServer().apply { start() } }
    @After fun tearDown() { mock.shutdown() }

    @Test
    fun `login exchanges password for one atomic session and uses official authorization header`() = runBlocking {
        mock.enqueue(authenticationResponse())
        val vault = FakeVault()
        val disposition = coordinator(vault).authenticateOrDefer(
            handle(auth()),
            Credentials.UsernamePassword("alice", "one-shot-password"),
        )

        assertTrue(disposition is AuthenticationDisposition.Authenticated)
        assertEquals("token-1", (vault.session?.credential as SessionCredential.AccessToken).accessToken)
        assertEquals("remote-1", vault.session?.remoteServerId)
        assertEquals("user-1", vault.session?.user?.userId)
        assertNull(vault.pending)
        assertFalse(vault.session.toString().contains("one-shot-password"))

        val request = mock.takeRequest()
        assertEquals("/emby/Users/AuthenticateByName", request.path)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Emby Client=\"MediaHub\""))
        assertNull(request.getHeader("X-Emby-Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"Username\":\"alice\""))
        assertTrue(body.contains("\"Pw\":\"one-shot-password\""))
    }

    @Test
    fun `login failures and malformed success never persist a session`() = runBlocking {
        val vault = FakeVault()
        mock.enqueue(MockResponse().setResponseCode(401))
        coordinator(vault).runCatchingLogin(handle(auth()), Credentials.UsernamePassword("a", "bad"))
        assertNull(vault.session)

        mock.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        coordinator(vault).runCatchingLogin(handle(auth()), Credentials.UsernamePassword("a", "b"))
        assertNull(vault.session)

        mock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"User":{"Id":"user-1"},"AccessToken":"","ServerId":"remote-1"}"""
        ))
        coordinator(vault).runCatchingLogin(handle(auth()), Credentials.UsernamePassword("a", "b"))
        assertNull(vault.session)
    }

    @Test
    fun `login requires token remote server id and user id`() = runBlocking {
        val invalidBodies = listOf(
            """{"User":{"Id":"user-1"},"AccessToken":"token-1"}""",
            """{"User":{"Name":"Alice"},"AccessToken":"token-1","ServerId":"remote-1"}""",
            """{"User":{"Id":"user-1"},"AccessToken":"","ServerId":"remote-1"}""",
        )
        invalidBodies.forEach { body ->
            val vault = FakeVault()
            mock.enqueue(MockResponse().setResponseCode(200).setBody(body))
            coordinator(vault).runCatchingLogin(handle(auth()), Credentials.UsernamePassword("a", "b"))
            assertNull(vault.session)
        }
    }

    @Test
    fun `restore verifies remote server anonymously before sending token`() = runBlocking {
        val vault = FakeVault(session = session())
        mock.enqueue(systemInfoResponse())
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice 2"}"""))

        val state = coordinator(vault).restore(handle(auth()))

        assertTrue(state is AuthenticationState.Authenticated)
        assertEquals("Alice 2", vault.session?.user?.displayName)
        val identityRequest = mock.takeRequest()
        assertEquals("/emby/System/Info/Public", identityRequest.path)
        assertNull(identityRequest.getHeader("X-Emby-Token"))
        val userRequest = mock.takeRequest()
        assertEquals("/emby/Users/user-1", userRequest.path)
        assertEquals("token-1", userRequest.getHeader("X-Emby-Token"))
        assertTrue(userRequest.getHeader("Authorization")!!.contains("UserId=\"user-1\""))
    }

    @Test
    fun `remote server mismatch clears session without ever sending token`() = runBlocking {
        val vault = FakeVault(session = session())
        mock.enqueue(systemInfoResponse(remoteServerId = "remote-other"))

        val state = coordinator(vault).restore(handle(auth()))

        assertTrue(state is AuthenticationState.SessionExpired)
        assertNull(vault.session)
        assertEquals(1, mock.requestCount)
        assertNull(mock.takeRequest().getHeader("X-Emby-Token"))
    }

    @Test
    fun `restore 401 clears session but 403 keeps it`() = runBlocking {
        val vault401 = FakeVault(session = session())
        mock.enqueue(systemInfoResponse())
        mock.enqueue(MockResponse().setResponseCode(401))
        assertTrue(coordinator(vault401).restore(handle(auth())) is AuthenticationState.SessionExpired)
        assertNull(vault401.session)

        val vault403 = FakeVault(session = session())
        mock.enqueue(systemInfoResponse())
        mock.enqueue(MockResponse().setResponseCode(403))
        assertTrue(coordinator(vault403).restore(handle(auth())) is AuthenticationState.Unavailable)
        assertEquals(session(), vault403.session)
    }

    @Test
    fun `malformed restore response preserves session`() = runBlocking {
        val existing = session()
        val vault = FakeVault(session = existing)
        mock.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        assertTrue(coordinator(vault).restore(handle(auth())) is AuthenticationState.Unavailable)
        assertEquals(existing, vault.session)
    }

    @Test
    fun `server errors preserve session`() = runBlocking {
        val existing = session()
        val vault = FakeVault(session = existing)
        mock.enqueue(MockResponse().setResponseCode(500))

        assertTrue(coordinator(vault).restore(handle(auth())) is AuthenticationState.Unavailable)
        assertEquals(existing, vault.session)
    }

    @Test
    fun `different authenticated user invalidates session`() = runBlocking {
        val vault = FakeVault(session = session())
        mock.enqueue(systemInfoResponse())
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-other","Name":"Other"}"""))

        assertTrue(coordinator(vault).restore(handle(auth())) is AuthenticationState.SessionExpired)
        assertNull(vault.session)
    }

    @Test
    fun `timeout preserves session`() = runBlocking {
        val existing = session()
        val vault = FakeVault(session = existing)
        mock.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertTrue(coordinator(vault).restore(handle(auth(timeoutMs = 200))) is AuthenticationState.Unavailable)
        assertEquals(existing, vault.session)
    }

    @Test
    fun `logout calls server and local vault clearing remains authoritative`() = runBlocking {
        val vault = FakeVault(session = session())
        mock.enqueue(MockResponse().setResponseCode(204))
        coordinator(vault).logout(handle(auth()))
        assertNull(vault.session)
        assertEquals("/emby/Sessions/Logout", mock.takeRequest().path)

        val offlineVault = FakeVault(session = session())
        mock.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coordinator(offlineVault).logout(handle(auth(timeoutMs = 200)))
        assertNull(offlineVault.session)
    }

    private fun auth(timeoutMs: Long = 5_000): EmbyAuthProvider {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val api = EmbyApiClient(
            mediaServer.baseUrl,
            ApiClient(client, logger = NoOpLogger),
            EmbyAuthorizationHeaderBuilder(ClientIdentity("MediaHub", "Android", "device-1", "0.1")),
        )
        return EmbyAuthProvider(mediaServer, api, EmbySessionValidator(mediaServer, api), NoOpLogger)
    }

    private fun handle(auth: EmbyAuthProvider): ProviderHandle = ProviderHandle(
        provider = object : MediaProvider {
            override val serverId = mediaServer.id
            override val descriptor = EmbyProvider.DESCRIPTOR
            override suspend fun testConnection(request: ConnectionTestRequest) = ConnectionStatus(ok = true)
        },
        auth = auth,
    )

    private fun coordinator(vault: FakeVault) = DefaultAuthenticationCoordinator(vault, NoOpLogger)

    private suspend fun DefaultAuthenticationCoordinator.runCatchingLogin(
        handle: ProviderHandle,
        credentials: Credentials,
    ) {
        runCatching { authenticateOrDefer(handle, credentials) }
    }

    private fun authenticationResponse() = MockResponse().setResponseCode(200).setBody(
        """{"User":{"Id":"user-1","Name":"Alice"},"AccessToken":"token-1","ServerId":"remote-1"}"""
    )

    private fun systemInfoResponse(remoteServerId: String = "remote-1") =
        MockResponse().setResponseCode(200).setBody(
            """{"Id":"$remoteServerId","ServerName":"Home","Version":"4.8.10.0","ProductName":"Emby Server"}"""
        )

    private class FakeVault(
        var pending: Credentials? = null,
        var session: AuthSession? = null,
    ) : CredentialVault {
        override suspend fun savePending(serverId: String, credentials: Credentials) { pending = credentials }
        override suspend fun readPending(serverId: String): Credentials? = pending
        override suspend fun saveSession(serverId: String, session: AuthSession) { this.session = session }
        override suspend fun readSession(serverId: String): AuthSession? = session
        override suspend fun clearPending(serverId: String) { pending = null }
        override suspend fun clear(serverId: String) { pending = null; session = null }
    }

    private companion object {
        fun session() = AuthSession(
            credential = SessionCredential.AccessToken("token-1"),
            user = MediaUser("server-1", "user-1", "Alice"),
            remoteServerId = "remote-1",
        )

        val NoOpLogger = object : Logger {
            override fun d(tag: LogTag, message: String) = Unit
            override fun i(tag: LogTag, message: String) = Unit
            override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
            override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
        }
    }
}
