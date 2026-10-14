package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Recreates all process-local provider state while retaining the encrypted-store contract. */
class WebDavCredentialRestartTest {
    private lateinit var mock: MockWebServer
    private val storage = FakeSecretStorage()
    private val logger = StdoutLogger()
    private val vault = CredentialVault(storage)
    private val tokenStore = TokenStore(storage)
    @Before fun setUp() { mock = MockWebServer().apply { start() } }
    @After fun tearDown() { mock.shutdown() }
    private fun server() = MediaServer("s1", "NAS", ServerType.WEBDAV,
        mock.url("/dav/").toString(), username = "alice", createdAtEpochMs = 0)
    private fun factory(vault: CredentialVault = this.vault) = WebDavProviderFactory(
        HttpClientFactory(logger), tokenStore, vault, WebDavCredentialCoordinator(), logger)
    private fun ok() = MockResponse().setResponseCode(207).setBody(
        WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "root")))
    private suspend fun login() {
        mock.enqueue(ok())
        val result = requireNotNull(factory().create(server()).auth)
            .authenticate(Credentials.UsernamePassword("alice", "restart-password"))
        assertTrue(result is AuthResult.Success)
    }

    @Test fun `persisted credential restores after coordinator and factory recreation`() = runBlocking {
        login()
        mock.enqueue(ok())
        val result = requireNotNull(factory().create(server()).auth).restoreSession()
        assertTrue("same persisted identity must restore after process state loss; actual=$result",
            result is AuthSessionState.Authenticated)
        assertEquals(2, mock.requestCount)
        mock.takeRequest()
        assertEquals(WebDavAuth.basicHeader("alice", "restart-password"), mock.takeRequest().getHeader("Authorization"))
    }

    @Test fun `restart never rebinds persisted password to changed endpoint or username`() = runBlocking {
        login()
        val fresh = factory()
        val server = server()
        for (changed in listOf(server.copy(endpoints = server.endpoints.map { it.copy(url = mock.url("/other/").toString()) }), server.copy(username = "bob"))) {
            assertEquals(AuthSessionState.SignedOut, requireNotNull(fresh.create(changed).auth).restoreSession())
        }
        assertEquals("changed identities send zero extra requests", 1, mock.requestCount)
    }

    @Test fun `legacy password without a persisted identity stays signed out`() = runBlocking {
        vault.save("s1", CredentialVault.CredentialKind.PASSWORD, "legacy-password")
        assertEquals(AuthSessionState.SignedOut, requireNotNull(factory().create(server()).auth).restoreSession())
        assertEquals(0, mock.requestCount)
    }
    @Test fun `failed atomic credential write retains old identity and password across restart`() = runBlocking {
        var failWrites = false
        val backing = FakeSecretStorage()
        val faulty = object : SecretStorage by backing {
            override suspend fun put(key: String, value: String) {
                if (failWrites) throw java.io.IOException("synthetic write failure")
                backing.put(key, value)
            }
        }
        val customVault = CredentialVault(faulty)
        val coordinator = WebDavCredentialCoordinator()
        val store = WebDavCredentialStore(customVault, coordinator)
        store.savePassword(server(), "old-password")
        val original = customVault.read("s1", CredentialVault.CredentialKind.WEBDAV_CREDENTIAL)
        failWrites = true
        assertTrue(runCatching { store.savePassword(server().copy(username = "bob"), "new-password") }.isFailure)
        assertEquals("identity+password are one unchanged durable record", original,
            customVault.read("s1", CredentialVault.CredentialKind.WEBDAV_CREDENTIAL))
        mock.enqueue(ok())
        assertTrue(requireNotNull(factory(customVault).create(server()).auth).restoreSession() is AuthSessionState.Authenticated)
        assertEquals(AuthSessionState.SignedOut,
            requireNotNull(factory(customVault).create(server().copy(username = "bob")).auth).restoreSession())
        assertEquals(WebDavAuth.basicHeader("alice", "old-password"), mock.takeRequest().getHeader("Authorization"))
        assertEquals(1, mock.requestCount)
    }

    @Test fun `corrupt and future credential binding records fail closed without network`() = runBlocking {
        for (raw in listOf("{\"version\":1,\"identity\":", "{\"version\":2,\"identity\":\"x\",\"password\":\"secret\"}")) {
            vault.save("s1", CredentialVault.CredentialKind.WEBDAV_CREDENTIAL, raw)
            assertEquals(AuthSessionState.SignedOut, requireNotNull(factory().create(server()).auth).restoreSession())
        }
        assertEquals(0, mock.requestCount)
    }

    @Test fun `logout clears persisted binding so recreated process cannot restore it`() = runBlocking {
        login()
        val auth = requireNotNull(factory().create(server()).auth)
        auth.logout()
        assertEquals(null, vault.read("s1", CredentialVault.CredentialKind.WEBDAV_CREDENTIAL))
        assertEquals(AuthSessionState.SignedOut, requireNotNull(factory().create(server()).auth).restoreSession())
        assertEquals(1, mock.requestCount)
    }

}
