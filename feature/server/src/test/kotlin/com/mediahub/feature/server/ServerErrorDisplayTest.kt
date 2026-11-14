package com.mediahub.feature.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerErrorDisplayTest {

    @Test
    fun `server url is replaced`() {
        val text = displayServerError("连接失败：https://192.168.1.10:8096/emby 超时")
        assertFalse(text.contains("192.168.1.10"))
        assertEquals("连接失败：<服务器地址> 超时", text)
    }

    @Test
    fun `secret query params are masked`() {
        val text = displayServerError("401 token=abc123&userId=7 rejected")
        assertFalse(text.contains("abc123"))
        assertEquals("401 token=<已隐藏>&userId=7 rejected", text)
    }

    @Test
    fun `whitespace is collapsed for multi line display`() {
        assertEquals("A B C", displayServerError("A\n\n  B\tC"))
    }

    @Test
    fun `blank error stays blank`() {
        assertEquals("", displayServerError("   "))
    }
}
