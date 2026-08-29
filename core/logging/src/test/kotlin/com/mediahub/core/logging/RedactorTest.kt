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
    fun `redacts complete quoted secrets containing spaces commas and escaped quotes`() {
        val json =
            """{"password":"correct horse, battery \"staple\"","access_token":"alpha beta,gamma"}"""

        val out = Redactor.redact(json)

        listOf("correct", "horse", "battery", "staple", "alpha", "beta", "gamma").forEach {
            assertFalse("quoted secret fragment must be removed: $it", out.contains(it))
        }
        assertTrue(out.contains("\"password\":\"****\""))
        assertTrue(out.contains("\"access_token\":\"****\""))
    }

    @Test
    fun `fails closed for unterminated quoted secrets including trailing backslash`() {
        val truncated = "{\"password\":\"secret tail"
        val truncatedAfterEscape = "{\"access_token\":\"secret tail\\"

        listOf(truncated, truncatedAfterEscape).forEach { input ->
            val out = Redactor.redact(input)
            assertFalse(out.contains("secret"))
            assertFalse(out.contains("tail"))
            assertTrue(out.endsWith(Redactor.REDACTED))
        }
    }

    @Test
    fun `redacts query parameter`() {
        val out = Redactor.redact("https://example.com/video?token=tok123&expires=999")
        assertFalse(out.contains("tok123"))
        assertTrue(out.contains("expires=999"))
    }

    @Test
    fun `redacts plain and percent encoded url userinfo credentials`() {
        val plain = Redactor.redact("https://alice:plain-secret@example.com/emby")
        val encoded = Redactor.redact("https://alice:p%40ss%3Aword@example.com/emby")

        listOf(plain, encoded).forEach { output ->
            assertFalse(output.contains("alice"))
            assertFalse(output.contains("plain-secret"))
            assertFalse(output.contains("p%40ss", ignoreCase = true))
            assertTrue(output.startsWith("https://****@example.com/"))
        }
    }

    @Test
    fun `redacts bare sensitive values separated by equals or colon ignoring case`() {
        val out = Redactor.redact(
            "api_key=plain-key PASSWORD: plain-password Token : mixed-case-token"
        )

        assertFalse(out.contains("plain-key"))
        assertFalse(out.contains("plain-password"))
        assertFalse(out.contains("mixed-case-token"))
        assertEquals(3, Regex(Regex.escape(Redactor.REDACTED)).findAll(out).count())
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

    // ---- Phase 1C-1：SearchTerm（用户搜索词）属隐私，日志只抹值不砍其它参数 ----

    @Test
    fun `redacts searchTerm value but keeps diagnostic query params`() {
        val url = "https://aaa.example.com/emby/Users/u1/Items" +
            "?SearchTerm=冰血暴&Recursive=true&StartIndex=0&Limit=30" +
            "&IncludeItemTypes=Movie,Series&EnableUserData=true"
        val out = Redactor.redact(url)
        assertFalse("搜索词必须从日志消失", out.contains("冰血暴"))
        assertTrue(out.contains(Redactor.REDACTED))
        // 非敏感诊断参数必须保留（否则调试搜索分页很痛苦）
        assertTrue(out.contains("Recursive=true"))
        assertTrue(out.contains("StartIndex=0"))
        assertTrue(out.contains("Limit=30"))
        assertTrue(out.contains("IncludeItemTypes=Movie,Series"))
        assertTrue(out.contains("EnableUserData=true"))
    }

    @Test
    fun `searchterm matching is case insensitive`() {
        val lower = Redactor.redact("https://e.com/Items?searchterm=secret-show&Limit=5")
        val upper = Redactor.redact("https://e.com/Items?SEARCHTERM=secret-show&Limit=5")
        val mixed = Redactor.redact("https://e.com/Items?SearchTerm=secret-show&Limit=5")
        listOf(lower, upper, mixed).forEach {
            assertFalse(it.contains("secret-show"))
            assertTrue(it.contains(Redactor.REDACTED))
            assertTrue(it.contains("Limit=5"))
        }
    }

    @Test
    fun `searchterm json body value is redacted`() {
        val json = """{"SearchTerm":"冰血暴","Limit":30}"""
        val out = Redactor.redact(json)
        assertFalse(out.contains("冰血暴"))
        assertTrue(out.contains("Limit"))
    }

    @Test
    fun `plain text containing word search is not touched`() {
        val text = "user searched over network: results=42"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun `percent encoded searchTerm value is redacted`() {
        // 真实请求里中文是 percent-encoded：%E5%86%B0%E8%A1%80%E6%9A%B4 == 冰血暴
        val url = "https://aaa.example.com/emby/Users/u1/Items" +
            "?SearchTerm=%E5%86%B0%E8%A1%80%E6%9A%B4&Recursive=true&StartIndex=0&Limit=30"
        val out = Redactor.redact(url)
        // encoded value 与解码关键词都不允许出现在日志
        assertFalse(out.contains("%E5%86%B0%E8%A1%80%E6%9A%B4"))
        assertFalse(out.contains("冰血暴"))
        assertTrue(out.contains(Redactor.REDACTED))
        assertTrue(out.contains("Recursive=true"))
        assertTrue(out.contains("StartIndex=0"))
        assertTrue(out.contains("Limit=30"))
    }

    @Test
    fun `memory logger redacts sensitive query in throwable message`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)
        val encoded = "%E5%86%B0%E8%A1%80%E6%9A%B4"

        logger.w(
            LogTag.NETWORK,
            "request failed",
            IllegalStateException("HTTP 500 GET https://e.com/Items?SearchTerm=$encoded&Limit=30"),
        )

        val line = buffer.snapshot().single()
        assertFalse(line.contains(encoded, ignoreCase = true))
        assertFalse(line.contains("冰血暴"))
        assertTrue(line.contains("SearchTerm=****", ignoreCase = true))
        assertTrue(line.contains("Limit=30"))
    }

    @Test
    fun `memory logger redacts complete quoted secret in throwable message`() {
        val buffer = LogBuffer()
        val logger = MemoryLogger(buffer)
        val secret = "correct horse, battery staple"

        logger.e(
            LogTag.NETWORK,
            "response failed",
            IllegalStateException("HTTP 500 body={\"password\":\"$secret\"}"),
        )

        val line = buffer.snapshot().single()
        assertFalse(line.contains(secret))
        assertFalse(line.contains("horse"))
        assertFalse(line.contains("staple"))
        assertTrue(line.contains("\"password\":\"****\""))
    }
}
