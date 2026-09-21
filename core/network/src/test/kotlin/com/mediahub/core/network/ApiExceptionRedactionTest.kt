package com.mediahub.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A2-4 第 2 项：ApiException 的 url / message 字段在构造时即脱敏——
 * 异常对象会被日志、crash report、toLogString() 全文带出去。
 */
class ApiExceptionRedactionTest {

    @Test
    fun `constructor redacts token in url field and default message`() {
        val e = ApiException(
            statusCode = 500,
            url = "https://host/emby/Items?token=abc123&x=1",
            method = "GET",
            requestId = "req-1",
        )

        assertFalse("url 字段必须脱敏：${e.url}", e.url.contains("abc123"))
        assertFalse("默认 message 必须脱敏：${e.message}", e.message!!.contains("abc123"))
        assertFalse("toLogString 必须脱敏：${e.toLogString()}", e.toLogString().contains("abc123"))
    }

    @Test
    fun `constructor redacts explicit message`() {
        val e = ApiException(
            statusCode = 400,
            url = "https://host/api",
            method = "POST",
            requestId = "req-2",
            message = "rejected because password=Sup3rS3cret",
        )

        assertFalse("显式 message 必须脱敏：${e.message}", e.message!!.contains("Sup3rS3cret"))
        // 非敏感部分保持可读
        assertEquals(true, e.message!!.contains("rejected because"))
    }
}
