package com.mediahub.core.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultTest {

    private val vault = CredentialVault(FakeSecretStorage())

    @Test
    fun `save and read by kind`() = runBlocking {
        vault.save("srv1", CredentialVault.CredentialKind.PASSWORD, "pwd-secret")
        vault.save("srv1", CredentialVault.CredentialKind.API_KEY, "key-123")

        assertEquals("pwd-secret", vault.read("srv1", CredentialVault.CredentialKind.PASSWORD))
        assertEquals("key-123", vault.read("srv1", CredentialVault.CredentialKind.API_KEY))
        assertTrue(vault.contains("srv1", CredentialVault.CredentialKind.PASSWORD))
        assertFalse(vault.contains("srv1", CredentialVault.CredentialKind.CLIENT_SECRET))
    }

    @Test
    fun `kinds are isolated per server`() = runBlocking {
        vault.save("srvA", CredentialVault.CredentialKind.PASSWORD, "pwd-a")
        assertNull(vault.read("srvB", CredentialVault.CredentialKind.PASSWORD))
    }

    @Test
    fun `clear removes all kinds for server`() = runBlocking {
        vault.save("srvX", CredentialVault.CredentialKind.PASSWORD, "p")
        vault.save("srvX", CredentialVault.CredentialKind.CLIENT_SECRET, "c")
        vault.clear("srvX")
        assertNull(vault.read("srvX", CredentialVault.CredentialKind.PASSWORD))
        assertNull(vault.read("srvX", CredentialVault.CredentialKind.CLIENT_SECRET))
    }

    @Test
    fun `remove single kind`() = runBlocking {
        vault.save("srvY", CredentialVault.CredentialKind.PASSWORD, "p")
        vault.save("srvY", CredentialVault.CredentialKind.CLIENT_SECRET, "c")
        vault.remove("srvY", CredentialVault.CredentialKind.PASSWORD)
        assertNull(vault.read("srvY", CredentialVault.CredentialKind.PASSWORD))
        assertEquals("c", vault.read("srvY", CredentialVault.CredentialKind.CLIENT_SECRET))
    }
}
