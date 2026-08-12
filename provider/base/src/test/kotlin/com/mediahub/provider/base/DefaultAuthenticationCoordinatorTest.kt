package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthenticationDisposition
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAuthenticationCoordinatorTest {
    @Test
    fun `successful authentication stores session and discards pending password`() = runTest {
        val vault = FakeVault()
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)
        val session = SessionCredential.AccessToken("access-token")

        val disposition = coordinator.authenticateOrDefer(
            handle(FakeAuthProvider(result = AuthResult.Success(user(), session))),
            Credentials.UsernamePassword("name", "password"),
        )

        assertTrue(disposition is AuthenticationDisposition.Authenticated)
        assertEquals(session, vault.session)
        assertNull(vault.pending)
    }

    @Test
    fun `unimplemented provider stores encrypted-vault pending credential for phase one`() = runTest {
        val vault = FakeVault()
        val coordinator = DefaultAuthenticationCoordinator(vault, NoOpLogger)
        val credentials = Credentials.UsernamePassword("name", "password")

        val disposition = coordinator.authenticateOrDefer(
            handle(FakeAuthProvider(notImplemented = true)),
            credentials,
        )

        assertEquals(AuthenticationDisposition.DeferredUntilProviderImplementation, disposition)
        assertEquals(credentials, vault.pending)
        assertNull(vault.session)
    }

    private fun handle(auth: MediaAuthProvider): ProviderHandle {
        val provider = object : MediaProvider {
            override val serverId = "server-1"
            override val descriptor = descriptor()
            override suspend fun testConnection(request: ConnectionTestRequest) = ConnectionStatus(ok = true)
        }
        return ProviderHandle(provider = provider, auth = auth)
    }

    private class FakeAuthProvider(
        private val result: AuthResult? = null,
        private val notImplemented: Boolean = false,
    ) : MediaAuthProvider {
        override suspend fun authenticate(credentials: Credentials): AuthResult {
            if (notImplemented) throw ProviderException.NotYetImplemented("server-1", "login")
            return requireNotNull(result)
        }
        override suspend fun refreshSession(): AuthResult = requireNotNull(result)
        override suspend fun logout() = Unit
        override suspend fun currentUser(): MediaUser? = user()
    }

    private class FakeVault : CredentialVault {
        var pending: Credentials? = null
        var session: SessionCredential? = null
        override suspend fun savePending(serverId: String, credentials: Credentials) { pending = credentials }
        override suspend fun readPending(serverId: String): Credentials? = pending
        override suspend fun saveSession(serverId: String, session: SessionCredential) { this.session = session }
        override suspend fun readSession(serverId: String): SessionCredential? = session
        override suspend fun clearPending(serverId: String) { pending = null }
        override suspend fun clear(serverId: String) { pending = null; session = null }
    }

    private companion object {
        fun user() = MediaUser("server-1", "user-1", "User")
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
