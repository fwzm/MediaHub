package com.mediahub.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化凭据头脱敏（Emby/Jellyfin 家族）。
 *
 * 生产路径：core/network 的 RedactingLoggingInterceptor 把整张请求头表交给
 * Redactor.redactHeaders() 打印（HttpClientFactory.kt:90）。Emby 家族把凭据
 * 放在结构化头里，而不是裸 token：
 * - X-Emby-Authorization: Emby UserId="u1", Client="MediaHub", Device="...", Token="..."
 * - X-MediaBrowser-Authorization（旧名，同一语义）
 * - X-Emby-Token / X-MediaBrowser-Token
 * 键名漏登记就会把整个值原样写进 logcat 与诊断导出。
 */
class RedactorStructuredHeaderTest {

    private val embyAuthValue =
        "Emby UserId=\"u1\", Client=\"MediaHub\", Device=\"Xiaomi 14 Ultra\", " +
            "DeviceId=\"pass\", Version=\"1.0\", Token=\"abc123sent\""

    @Test
    fun `x emby authorization header value is redacted`() {
        val headers = mapOf(
            "X-Emby-Authorization" to embyAuthValue,
            "Content-Type" to "application/json",
        )
        val out = Redactor.redactHeaders(headers)
        assertEquals(Redactor.REDACTED, out["X-Emby-Authorization"])
        assertEquals("application/json", out["Content-Type"])
        assertFalse("整个头表不得残留 Token 值", out.values.joinToString(" ").contains("abc123sent"))
        assertFalse("整个头表不得残留 DeviceId 值", out.values.joinToString(" ").contains("pass"))
    }

    @Test
    fun `x mediabrowser authorization header value is redacted`() {
        val out = Redactor.redactHeaders(mapOf("X-MediaBrowser-Authorization" to embyAuthValue))
        assertEquals(Redactor.REDACTED, out["X-MediaBrowser-Authorization"])
    }

    @Test
    fun `x emby token header value is redacted`() {
        val out = Redactor.redactHeaders(mapOf("X-Emby-Token" to "abc123sent"))
        assertEquals(Redactor.REDACTED, out["X-Emby-Token"])
    }

    @Test
    fun `unknown structured credential header is redacted by key shape`() {
        // 未来新增的 X-Foo-Token / X-Bar-Authorization 也必须 fail-closed
        val out = Redactor.redactHeaders(
            mapOf(
                "X-Custom-Token" to "abc123sent",
                "X-NewServer-Authorization" to "Bearer abc123sent",
                "X-Session-Secret" to "Sup3rS3cret",
            )
        )
        assertEquals(Redactor.REDACTED, out["X-Custom-Token"])
        assertEquals(Redactor.REDACTED, out["X-NewServer-Authorization"])
        assertEquals(Redactor.REDACTED, out["X-Session-Secret"])
    }

    @Test
    fun `benign headers are preserved verbatim`() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "MediaHub/1.0",
            "Content-Length" to "42",
            "X-Request-Id" to "req-123",
            "Range" to "bytes=0-1023",
        )
        val out = Redactor.redactHeaders(headers)
        assertEquals(headers, out)
    }

    @Test
    fun `isSensitiveHeader agrees with redactHeaders for every probed key`() {
        val keys = listOf(
            "Authorization", "X-Emby-Authorization", "X-MediaBrowser-Authorization",
            "X-Emby-Token", "X-MediaBrowser-Token", "X-Plex-Token", "Cookie", "Set-Cookie",
            "X-Api-Key", "X-Session-Secret", "X-Custom-Token",
            "Content-Type", "Accept", "X-Request-Id", "Range", "Content-Length",
        )
        for (key in keys) {
            val redacted = Redactor.redactHeaders(mapOf(key to "value-xyz"))[key]
            assertEquals(
                "键 $key 的 isSensitiveHeader 与 redactHeaders 判定必须一致",
                Redactor.isSensitiveHeader(key),
                redacted == Redactor.REDACTED,
            )
        }
    }

    @Test
    fun `header dump string keeps no structured credential value`() {
        // 即使调用方直接把头表拼成字符串交给 redact()，也不得残留凭据
        val dump = "headers={X-Emby-Authorization=$embyAuthValue, X-Emby-Token=abc123sent}"
        val out = Redactor.redact(dump)
        assertFalse("结构化头值必须消失：$out", out.contains("abc123sent"))
        assertFalse("DeviceId 值必须消失：$out", out.contains("pass"))
    }
}
