package com.mediahub.provider.api

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件源管理错误文案（审计条目 83）：
 * ProviderException 的 code → 准确的用户可读文案；禁止直接外显原始 message。
 *
 * 关键语义：403 是访问策略拒绝（**不是密码错误**），401 才是登录过期——
 * 两者文案必须能被用户区分。
 */
class ProviderErrorTextTest {

    private fun http(status: Int) = ProviderException.Http("srv", status, "https://media.invalid/x", "GET")

    @Test
    fun `http 403 says access denied and explicitly not a password error`() {
        val text = ProviderErrorText.from(http(403))
        assertTrue("必须指出是拒绝访问（实际：$text）", text.contains("拒绝访问"))
        assertTrue("必须给出 403 状态码（实际：$text）", text.contains("403"))
        assertTrue("必须明确不是密码错误（实际：$text）", text.contains("不是密码错误"))
        assertFalse("403 文案不得暗示密码错误（实际：$text）", text.contains("密码错误") && !text.contains("不是密码错误"))
    }

    @Test
    fun `http 401 says login expired and differs from 403`() {
        val text = ProviderErrorText.from(http(401))
        assertEquals("登录已过期（HTTP 401），请重新登录", text)
        assertFalse("401 文案不得混入访问拒绝说明（实际：$text）", text.contains("拒绝访问"))
        assertTrue("401 与 403 文案必须可区分", text != ProviderErrorText.from(http(403)))
    }

    @Test
    fun `http status table is accurate`() {
        assertEquals(
            "服务器上找不到该地址（HTTP 404），请检查地址、端口或路径",
            ProviderErrorText.from(http(404)),
        )
        assertEquals(
            "请求过于频繁（HTTP 429），请稍后重试",
            ProviderErrorText.from(http(429)),
        )
        assertTrue(ProviderErrorText.from(http(503)).contains("HTTP 503"))
        assertTrue(ProviderErrorText.from(http(500)).contains("服务器内部错误"))
    }

    @Test
    fun `auth codes are distinguished`() {
        assertEquals("用户名或密码错误", ProviderErrorText.from(ProviderException.AuthFailed("srv")))
        assertEquals("登录状态已过期，请重新登录", ProviderErrorText.from(ProviderException.AuthExpired("srv")))
        assertEquals("需要登录后才能访问，请重新登录", ProviderErrorText.from(ProviderException.AuthRequired("srv")))
    }

    @Test
    fun `network sub causes are distinguished`() {
        assertEquals(
            "无法解析服务器地址（DNS），请检查域名拼写，或在局域网内直接使用 IP",
            ProviderErrorText.from(ProviderException.Network("srv", UnknownHostException("media.invalid"))),
        )
        assertEquals(
            "服务器拒绝建立连接，请确认端口已开放且未被防火墙拦截",
            ProviderErrorText.from(ProviderException.Network("srv", ConnectException("refused"))),
        )
        assertEquals(
            "连接超时，请检查网络与服务器地址",
            ProviderErrorText.from(ProviderException.Network("srv", SocketTimeoutException("timeout"))),
        )
        assertEquals(
            "网络连接失败，请检查网络与服务器地址",
            ProviderErrorText.from(ProviderException.Network("srv", IOException("broken"))),
        )
    }

    @Test
    fun `other codes map to accurate text without leaking message`() {
        assertEquals(
            "服务器响应无法解析，可能不是受支持的媒体服务器",
            ProviderErrorText.from(ProviderException.Parse("srv", IOException("bad json"))),
        )
        assertEquals(
            "当前数据源暂不支持该操作",
            ProviderErrorText.from(ProviderException.NotYetImplemented("srv", "字幕")),
        )
        assertEquals(
            "服务器上找不到请求的内容（404）",
            ProviderErrorText.from(ProviderException.NotFound("srv", "影片")),
        )
        assertEquals(
            "请求过于频繁（429），请稍后重试",
            ProviderErrorText.from(ProviderException.RateLimited("srv")),
        )
        assertEquals(
            "未知错误，请稍后重试或查看诊断日志",
            ProviderErrorText.from(ProviderException.Unknown("srv")),
        )
        assertEquals(
            "本地目录授权失效或不可访问，请重新选择目录",
            ProviderErrorText.from(ProviderException.DirectoryAccessRequired("srv")),
        )
        val raw = ProviderException.Connection("srv", "raw detail https://secret.invalid/?token=abc")
            .let { ProviderErrorText.from(it) }
        assertFalse("不得外显原始 message（实际：$raw）", raw.contains("secret.invalid"))
        assertFalse("不得外显 token（实际：$raw）", raw.contains("token"))
    }

    @Test
    fun `stored last error text is mapped and never shown raw`() {
        val forbidden = ProviderErrorText.fromStoredLastError("服务器返回 403（GET）")
        assertTrue("存储的 403 文本必须映射为访问拒绝（实际：$forbidden）", forbidden.contains("拒绝访问"))
        assertTrue(forbidden.contains("不是密码错误"))

        val fallback = ProviderErrorText.fromStoredLastError("NoSuchMethodError: internal/impl")
        assertFalse("无法识别时不得外显原始文本（实际：$fallback）", fallback.contains("NoSuchMethodError"))
        assertTrue(fallback.isNotBlank())
    }
}
