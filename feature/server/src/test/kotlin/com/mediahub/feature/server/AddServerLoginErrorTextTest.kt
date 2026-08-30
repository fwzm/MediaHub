package com.mediahub.feature.server

import com.mediahub.provider.api.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 登录失败文案映射（ADR-039 review hardening 衍生 UX 修复）：403 给可操作指引。 */
class AddServerLoginErrorTextTest {

    private fun http(status: Int) = ProviderException.Http("srv", status, "https://x", "POST")

    @Test
    fun `http 403 gives actionable remote access hint instead of bare status code`() {
        assertEquals(
            "服务器拒绝登录（HTTP 403）。请检查该账号是否允许远程连接，" +
                "以及 Jellyfin 的反向代理 / Known Proxies 配置。",
            AddServerViewModel.loginErrorText(http(403)),
        )
    }

    @Test
    fun `http 5xx keeps server error wording`() {
        assertEquals("服务器错误（HTTP 503）", AddServerViewModel.loginErrorText(http(503)))
    }

    @Test
    fun `http 404 keeps bare status wording`() {
        assertEquals("HTTP 404", AddServerViewModel.loginErrorText(http(404)))
    }

    @Test
    fun `auth failed keeps wrong password wording`() {
        assertEquals(
            "用户名或密码错误",
            AddServerViewModel.loginErrorText(ProviderException.AuthFailed("srv")),
        )
    }

    @Test
    fun `network keeps check address wording`() {
        assertEquals(
            "网络错误，请检查服务器地址",
            AddServerViewModel.loginErrorText(
                ProviderException.Network("srv", java.io.IOException("x")),
            ),
        )
    }
}
