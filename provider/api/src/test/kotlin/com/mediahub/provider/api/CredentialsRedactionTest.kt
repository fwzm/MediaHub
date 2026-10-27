package com.mediahub.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsRedactionTest {
    @Test
    fun usernamePasswordIsRedactedWithoutChangingAuthenticationInput() {
        val credentials = Credentials.UsernamePassword("fixture-account-71", "fixture-password-83")
        assertEquals("fixture-account-71", credentials.username)
        assertEquals("fixture-password-83", credentials.password)
        assertRedacted(credentials, "UsernamePassword", credentials.username, credentials.password)
        assertRedacted(credentials.copy(password = "replacement-password-91"), "UsernamePassword",
            credentials.username, "replacement-password-91")
    }

    @Test
    fun webDavIsRedactedWithoutChangingAuthenticationInput() {
        val credentials = Credentials.WebDav("fixture-dav-account-17", "fixture-dav-password-29")
        assertEquals("fixture-dav-account-17", credentials.username)
        assertEquals("fixture-dav-password-29", credentials.password)
        assertRedacted(credentials, "WebDav", credentials.username, credentials.password)
        assertRedacted(credentials.copy(username = "replacement-dav-account-31"), "WebDav",
            "replacement-dav-account-31", credentials.password)
    }

    @Test
    fun bearerTokenIsRedactedWithoutChangingAuthenticationInput() {
        val credentials = Credentials.BearerToken("fixture-bearer-secret-37")
        assertEquals("fixture-bearer-secret-37", credentials.token)
        assertRedacted(credentials, "BearerToken", credentials.token)
        assertRedacted(credentials.copy(token = "replacement-bearer-secret-41"), "BearerToken",
            "replacement-bearer-secret-41")
    }

    @Test
    fun apiKeyIsRedactedWithoutChangingAuthenticationInput() {
        val credentials = Credentials.ApiKey("fixture-api-secret-43")
        assertEquals("fixture-api-secret-43", credentials.apiKey)
        assertRedacted(credentials, "ApiKey", credentials.apiKey)
        assertRedacted(credentials.copy(apiKey = "replacement-api-secret-47"), "ApiKey",
            "replacement-api-secret-47")
    }

    private fun assertRedacted(credentials: Credentials, kind: String, vararg privateFields: String) {
        val diagnosticContexts = listOf(
            credentials.toString(),
            "authentication input=$credentials",
            listOf(credentials).toString(),
            mapOf("input" to credentials).toString(),
        )
        diagnosticContexts.forEach { rendered ->
            assertTrue("Credential kind must remain identifiable", rendered.contains(kind))
            privateFields.forEach { field ->
                assertFalse("Credential fields must not appear in diagnostic strings", rendered.contains(field))
            }
        }
    }
}
