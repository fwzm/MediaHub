package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.AuthenticationDisposition
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.api.SessionRestoreResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAuthenticationCoordinatorTest {
    @Test
    fun `successful authentication atomically stores session and discards password`() = runTest {
        val vault = FakeVault()
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)
        val session = session()

        val disposition = coordinator.authenticateOrDefer(
            handle(FakeAuthProvider(authResult = AuthResult.Success(session))),
            Credentials.UsernamePassword("name", "password"),
        )

        assertTrue(disposition is AuthenticationDisposition.Authenticated)
        assertEquals(session, vault.session)
        assertNull(vault.pending)
    }

    @Test
    fun `unimplemented non-password flow may preserve pending credential`() = runTest {
        val vault = FakeVault()
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)
        val credentials = Credentials.DeviceCode("device-code")

        val disposition = coordinator.authenticateOrDefer(handle(), credentials)

        assertEquals(AuthenticationDisposition.DeferredUntilProviderImplementation, disposition)
        assertEquals(credentials, vault.pending)
    }

    @Test
    fun `unimplemented username password auth is never deferred to storage`() = runTest {
        val vault = FakeVault()
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)

        runCatching {
            coordinator.authenticateOrDefer(
                handle(),
                Credentials.UsernamePassword("name", "password"),
            )
        }

        assertNull(vault.pending)
        assertNull(vault.session)
    }

    @Test
    fun `restore clears only a definitely invalidated session`() = runTest {
        val vault = FakeVault(session = session())
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)

        val state = coordinator.restore(
            handle(FakeAuthProvider(restoreResult = SessionRestoreResult.Invalidated(ProviderException.AuthExpired(SERVER_ID))))
        )

        assertTrue(state is AuthenticationState.SessionExpired)
        assertNull(vault.session)
    }

    @Test
    fun `restore keeps session when provider is temporarily unavailable`() = runTest {
        val existing = session()
        val vault = FakeVault(session = existing)
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)

        val state = coordinator.restore(
            handle(FakeAuthProvider(restoreResult = SessionRestoreResult.Unavailable(ProviderException.Parse(SERVER_ID))))
        )

        assertTrue(state is AuthenticationState.Unavailable)
        assertEquals(existing, vault.session)
    }

    @Test
    fun `logout clears vault even when remote logout fails`() = runTest {
        val vault = FakeVault(session = session())
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)

        coordinator.logout(handle(FakeAuthProvider(logoutFailure = ProviderException.Network(SERVER_ID))))

        assertNull(vault.session)
    }

    private fun handle(auth: MediaAuthProvider? = null): ProviderHandle {
        val provider = object : MediaProvider {
            override val serverId = SERVER_ID
            override val descriptor = descriptor()
            override suspend fun testConnection(request: ConnectionTestRequest) = ConnectionStatus(ok = true)
        }
        return ProviderHandle(provider = provider, auth = auth)
    }

    private class FakeAuthProvider(
        private val authResult: AuthResult = AuthResult.Failure(ProviderException.AuthFailed(SERVER_ID)),
        private val restoreResult: SessionRestoreResult = SessionRestoreResult.Restored(session()),
        private val logoutFailure: Exception? = null,
    ) : MediaAuthProvider {
        override suspend fun authenticate(credentials: Credentials): AuthResult = authResult
        override suspend fun restoreSession(session: AuthSession): SessionRestoreResult = restoreResult
        override suspend fun logout(session: AuthSession) {
            logoutFailure?.let { throw it }
        }
    }

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
        const val SERVER_ID = "server-1"
        fun user() = MediaUser(SERVER_ID, "user-1", "User")
        fun session() = AuthSession(SessionCredential.AccessToken("token"), user(), "remote-1")
        fun descriptor() = ProviderDescriptor(
            providerId = "auth-test",
            displayName = "Auth Test",
            description = "test",
            category = ProviderCategory.MEDIA_SERVER,
            capabilities = setOf(ProviderCapability.AUTH),
            authMethod = AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.AVAILABLE,
        )

        val NoOpLogger = object : Logger {
            override fun d(tag: LogTag, message: String) = Unit
            override fun i(tag: LogTag, message: String) = Unit
            override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
            override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
        }
    }
}
