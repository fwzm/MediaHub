package com.mediahub.provider.api

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A2-4 第 1 项：ProviderException 的 message 与 cause 链不得携带原始异常文本。
 * crash report / 日志会把 message、cause.message、stackTraceToString 全文带出去，
 * 原始 cause 文本可能含 URL / Token / 密码。字段（statusCode/url 等）保留供结构化诊断。
 */
class ProviderExceptionSanitizationTest {

    private val secret = "Sup3rS3cret"

    private fun assertNoSecret(vararg texts: String?) {
        texts.filterNotNull().forEach { text ->
            assertFalse("输出不得含合成秘密，实际：$text", text.contains(secret))
        }
    }

    private fun printStackTraceOf(e: Throwable): String {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured))
        try {
            e.printStackTrace()
        } finally {
            System.setErr(original)
        }
        return captured.toString()
    }

    @Test
    fun `network keeps fixed message and sanitized cause chain`() {
        val inner = IllegalStateException("api_key=$secret at https://host/emby/Users?api_key=$secret")
        val outer = RuntimeException("connect failed password=$secret", inner)

        val e = ProviderException.Network("srv-1", outer)

        assertEquals("网络错误，请检查网络连接", e.message)
        assertNoSecret(
            e.message,
            e.cause?.message,
            e.cause?.cause?.message,
            e.stackTraceToString(),
            printStackTraceOf(e),
        )
    }

    @Test
    fun `unknown keeps fixed message and sanitized cause chain`() {
        val cause = RuntimeException("boom token=$secret")
        val e = ProviderException.Unknown("srv-1", cause)

        assertEquals("未知错误", e.message)
        assertNoSecret(
            e.message,
            e.cause?.message,
            e.stackTraceToString(),
            printStackTraceOf(e),
        )
    }

    @Test
    fun `http message drops url but keeps structured fields`() {
        val url = "https://host/emby/Users?api_key=$secret"
        val e = ProviderException.Http("srv-1", 500, url, "GET", "req-1")

        assertEquals("服务器返回 500（GET）", e.message)
        assertNoSecret(e.message, e.stackTraceToString(), printStackTraceOf(e))
        // 字段保留：结构化诊断仍可用，只是不再拼进 message
        assertEquals(500, e.statusCode)
        assertEquals(url, e.url)
        assertEquals("GET", e.method)
        assertEquals("req-1", e.requestId)
    }

    @Test
    fun `sanitized cause keeps original class names and frames for diagnosis`() {
        val cause = RuntimeException("token=$secret")
        val e = ProviderException.Network("srv-1", cause)

        // 类名与帧保留（定位需要），仅文本被消毒
        val trace = e.stackTraceToString()
        assert(trace.contains("java.lang.RuntimeException")) { "cause 类名必须保留：$trace" }
        assert(e.cause != null && e.cause!!.stackTrace.contentEquals(cause.stackTrace)) {
            "cause 帧必须转发原 cause 的 stackTrace"
        }
    }
}
