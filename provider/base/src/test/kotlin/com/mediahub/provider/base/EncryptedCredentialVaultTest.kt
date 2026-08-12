package com.mediahub.provider.base

import com.mediahub.core.security.SecretStorage
import com.mediahub.model.MediaUser
import com.mediahub.provider.api.AuthSession
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.SessionCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedCredentialVaultTest {
    @Test
    fun `pending password round trips without plaintext storage`() = runTest {
        val storage = MemorySecretStorage()
        val vault = EncryptedCredentialVault(storage)
        val credentials = Credentials.UsernamePassword("用户|name", "p@ss|秘密")

        vault.savePending("server-1", credentials)

        assertEquals(credentials, vault.readPending("server-1"))
        assertFalse(storage.values.values.single().contains("p@ss"))
        assertFalse(credentials.toString().contains("秘密"))
    }

    @Test
    fun `session replaces pending credential and clear removes both`() = runTest {
        val storage = MemorySecretStorage()
        val vault = EncryptedCredentialVault(storage)
        vault.savePending("server-1", Credentials.BasicAuth("name", "password"))
        val session = AuthSession(
            credential = SessionCredential.OAuth2("access", "refresh", 42L),
            user = MediaUser("server-1", "user-1", "Alice"),
            remoteServerId = "remote-1",
        )

        vault.saveSession("server-1", session)
        vault.clearPending("server-1")

        assertNull(vault.readPending("server-1"))
        assertEquals(session, vault.readSession("server-1"))
        vault.clear("server-1")
        assertNull(vault.readSession("server-1"))
    }

    private class MemorySecretStorage : SecretStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun get(key: String): String? = values[key]
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun contains(key: String): Boolean = key in values
    }
}
