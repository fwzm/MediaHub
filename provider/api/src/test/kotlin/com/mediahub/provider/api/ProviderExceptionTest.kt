package com.mediahub.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class ProviderExceptionTest {

    @Test
    fun `network user message does not copy sensitive cause message`() {
        val cause = IllegalStateException(
            "GET https://alice:password@example.com/Items?SearchTerm=private-title"
        )

        val exception = ProviderException.Network("server-1", cause)

        assertEquals("网络错误，请检查网络连接", exception.message)
        assertFalse(exception.message.orEmpty().contains("private-title"))
        assertFalse(exception.message.orEmpty().contains("password"))
        assertNotSame(cause, exception.cause)
        assertEquals(cause.javaClass.name, exception.cause?.message)
        assertFalse(exception.stackTraceToString().contains("private-title"))
        assertFalse(exception.stackTraceToString().contains("password"))
    }

    @Test
    fun `unknown user message does not copy sensitive cause message`() {
        val cause = IllegalArgumentException("api_key=plain-secret")

        val exception = ProviderException.Unknown("server-1", cause)

        assertEquals("发生未知错误", exception.message)
        assertFalse(exception.message.orEmpty().contains("plain-secret"))
        assertNotSame(cause, exception.cause)
        assertEquals(cause.javaClass.name, exception.cause?.message)
        assertFalse(exception.stackTraceToString().contains("plain-secret"))
    }

    @Test
    fun `parse exception sanitizes access token from cause chain`() {
        val cause = IllegalArgumentException(
            "Unexpected JSON near AccessToken=server-secret-token SearchTerm=private-title"
        )

        val exception = ProviderException.Parse("server-1", cause)

        assertEquals("数据解析失败", exception.message)
        assertNotSame(cause, exception.cause)
        assertEquals(cause.javaClass.name, exception.cause?.message)
        assertFalse(exception.stackTraceToString().contains("server-secret-token"))
        assertFalse(exception.stackTraceToString().contains("private-title"))
    }
}
