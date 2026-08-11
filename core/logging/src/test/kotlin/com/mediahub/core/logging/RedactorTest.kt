package com.mediahub.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `redacts authorization bearer header`() {
        val out = Redactor.redact("Authorization: Bearer abc123token")
        assertTrue(out.contains(Redactor.REDACTED))
        assertFalse(out.contains("abc123token"))
    }

    @Test
    fun `redacts cookie header`() {
        val out = Redactor.redact("Cookie: session=xyz789; foo=bar")
        assertTrue(out.contains(Redactor.REDACTED))
        assertFalse(out.contains("xyz789"))
    }

    @Test
    fun `redacts json token values but keeps normal fields`() {
        val json = """{"access_token":"secretvalue","name":"normal","refresh_token":"r-secret"}"""
        val out = Redactor.redact(json)
        assertFalse(out.contains("secretvalue"))
        assertFalse(out.contains("r-secret"))
        assertTrue(out.contains("normal"))
    }

    @Test
    fun `redacts query parameter`() {
        val out = Redactor.redact("https://example.com/video?token=tok123&expires=999")
        assertFalse(out.contains("tok123"))
        assertTrue(out.contains("expires=999"))
    }

    @Test
    fun `keeps plain text untouched`() {
        val text = "hello world, this is a normal log line"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun `redacts sensitive headers map and keeps others`() {
        val headers = mapOf(
            "Authorization" to "Bearer x",
            "Cookie" to "a=b",
            "Content-Type" to "application/json",
        )
        val out = Redactor.redactHeaders(headers)
        assertEquals(Redactor.REDACTED, out["Authorization"])
        assertEquals(Redactor.REDACTED, out["Cookie"])
        assertEquals("application/json", out["Content-Type"])
    }

    @Test
    fun `null and blank are safe`() {
        assertEquals("", Redactor.redact(null))
        assertEquals("", Redactor.redact(""))
    }
}
