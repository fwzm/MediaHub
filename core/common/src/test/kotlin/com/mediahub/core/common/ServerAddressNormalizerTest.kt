package com.mediahub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ServerAddressNormalizer 测试（Phase 1I）：覆盖工作包要求的全部输入形态与拒绝项。
 * 规范化结果是测试连接/登录/保存/线路质量测试的唯一权威 URL。
 */
class ServerAddressNormalizerTest {

    private fun ok(raw: String, preferHttps: Boolean = true): ServerAddressNormalizer.NormalizedAddress =
        when (val r = ServerAddressNormalizer.normalize(raw, preferHttps)) {
            is ServerAddressNormalizer.Result.Ok -> r.address
            is ServerAddressNormalizer.Result.Invalid -> throw AssertionError(
                "应为 Ok，实际 Invalid: ${r.error.userMessage} (raw=$raw)"
            )
        }

    private fun error(raw: String, preferHttps: Boolean = true): ServerAddressNormalizer.Error =
        when (val r = ServerAddressNormalizer.normalize(raw, preferHttps)) {
            is ServerAddressNormalizer.Result.Invalid -> r.error
            is ServerAddressNormalizer.Result.Ok -> throw AssertionError(
                "应为 Invalid，实际 Ok: ${r.address.url} (raw=$raw)"
            )
        }

    // ---- 工作包要求的输入形态 ----

    @Test
    fun `bare domain completes to https by default`() {
        val a = ok("media.example")
        assertEquals("https://media.example", a.url)
        assertEquals("https", a.scheme)
        assertNull(a.port)
        assertEquals("", a.path)
    }

    @Test
    fun `bare domain uses http when switch off`() {
        val a = ok("media.example", preferHttps = false)
        assertEquals("http://media.example", a.url)
        assertEquals("http", a.scheme)
    }

    @Test
    fun `host with port keeps explicit port`() {
        val a = ok("media.example:8920")
        assertEquals("https://media.example:8920", a.url)
        assertEquals(8920, a.port)
    }

    @Test
    fun `ipv4 host with port`() {
        val a = ok("192.168.1.100:8096", preferHttps = false)
        assertEquals("http://192.168.1.100:8096", a.url)
        assertEquals("192.168.1.100", a.host)
    }

    @Test
    fun `reverse proxy subpath preserved with case`() {
        val a = ok("media.example/JeLLyFin")
        assertEquals("https://media.example/JeLLyFin", a.url)
        assertEquals("/JeLLyFin", a.path)
    }

    @Test
    fun `full https url paste is authoritative`() {
        val a = ok("https://media.example:8920/jellyfin")
        assertEquals("https://media.example:8920/jellyfin", a.url)
        assertEquals("https", a.scheme)
        assertEquals(8920, a.port)
        assertEquals("/jellyfin", a.path)
    }

    @Test
    fun `full http url paste keeps http`() {
        val a = ok("http://192.168.1.100:8096")
        assertEquals("http://192.168.1.100:8096", a.url)
        assertEquals("http", a.scheme)
    }

    @Test
    fun `ipv6 with brackets and port and subpath`() {
        val a = ok("[2001:db8::1]:8920/jellyfin")
        assertEquals("https://[2001:db8::1]:8920/jellyfin", a.url)
        assertEquals("[2001:db8::1]", a.host)
        assertEquals(8920, a.port)
    }

    @Test
    fun `explicit scheme is case insensitive and normalized`() {
        assertEquals("https://Media.Example", ok("HTTPS://Media.Example").url)
        assertEquals("http", ok("HTTP://media.example").scheme)
    }

    @Test
    fun `existing percent encoding preserved without double encoding`() {
        val a = ok("media.example/path%2Fdeep")
        assertEquals("https://media.example/path%2Fdeep", a.url)
    }

    @Test
    fun `trailing slash trimmed like legacy storage semantics`() {
        assertEquals("https://media.example", ok("media.example/").url)
        assertEquals("https://media.example/jellyfin", ok("media.example/jellyfin/").url)
        assertEquals("https://media.example", ok("https://media.example///").url)
    }

    @Test
    fun `toggle only changes scheme keeping port and subpath`() {
        val raw = "media.example:8920/jellyfin"
        assertEquals("https://media.example:8920/jellyfin", ok(raw, preferHttps = true).url)
        assertEquals("http://media.example:8920/jellyfin", ok(raw, preferHttps = false).url)
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("https://media.example", ok("  media.example  ").url)
    }

    // ---- 拒绝项 ----

    @Test
    fun `empty input rejected`() {
        assertTrue(error("") is ServerAddressNormalizer.Error.Empty)
        assertTrue(error("   ") is ServerAddressNormalizer.Error.Empty)
    }

    @Test
    fun `unsupported scheme rejected with name`() {
        val e = error("ftp://media.example")
        assertTrue(e is ServerAddressNormalizer.Error.UnsupportedScheme)
        assertEquals("ftp", (e as ServerAddressNormalizer.Error.UnsupportedScheme).scheme)
    }

    @Test
    fun `double scheme never produces duplicated protocol`() {
        // "https://https://x" → authority "https:" 端口为空 → 拒绝，不产生 https://https://
        val e = error("https://https://media.example")
        assertTrue(e is ServerAddressNormalizer.Error.InvalidPort)
    }

    @Test
    fun `invalid ports rejected`() {
        assertTrue(error("media.example:0") is ServerAddressNormalizer.Error.InvalidPort)
        assertTrue(error("media.example:65536") is ServerAddressNormalizer.Error.InvalidPort)
        assertTrue(error("media.example:abc") is ServerAddressNormalizer.Error.InvalidPort)
        assertTrue(error("media.example:") is ServerAddressNormalizer.Error.InvalidPort)
        assertTrue(error("media.example:-1") is ServerAddressNormalizer.Error.InvalidPort)
    }

    @Test
    fun `port boundary 65535 accepted`() {
        assertEquals(65535, ok("media.example:65535").port)
    }

    @Test
    fun `userinfo rejected`() {
        assertTrue(error("user:password@media.example") is ServerAddressNormalizer.Error.UserInfoNotAllowed)
        assertTrue(error("https://user@media.example") is ServerAddressNormalizer.Error.UserInfoNotAllowed)
    }

    @Test
    fun `query and fragment rejected explicitly`() {
        val q = error("media.example?x=1")
        assertTrue(q is ServerAddressNormalizer.Error.QueryOrFragmentNotAllowed)
        assertTrue((q as ServerAddressNormalizer.Error.QueryOrFragmentNotAllowed).hasQuery)
        val f = error("media.example#frag")
        assertTrue(f is ServerAddressNormalizer.Error.QueryOrFragmentNotAllowed)
        assertTrue((f as ServerAddressNormalizer.Error.QueryOrFragmentNotAllowed).hasFragment)
        // 路径后携带 query 同样拒绝（服务器根地址不承载 query）
        assertTrue(error("media.example/jellyfin?x=1") is ServerAddressNormalizer.Error.QueryOrFragmentNotAllowed)
    }

    @Test
    fun `invalid hosts rejected`() {
        assertTrue(error(":8920") is ServerAddressNormalizer.Error.InvalidHost)
        assertTrue(error("[2001:db8::1") is ServerAddressNormalizer.Error.InvalidHost)
        assertTrue(error("media exam ple") is ServerAddressNormalizer.Error.InvalidHost)
    }

    @Test
    fun `ipv6 without brackets rejected as invalid port or host`() {
        val e = error("2001:db8::1")
        assertTrue(
            e is ServerAddressNormalizer.Error.InvalidPort || e is ServerAddressNormalizer.Error.InvalidHost,
        )
    }

    @Test
    fun `non ascii host rejected even when uri parser is lenient`() {
        // 输入法串键/全角冒号场景：么嗲example：89media0（全角：不是 ASCII 冒号，
        // 整串进 host）→ 必须拒绝，不得产出永远连不上的"合法"假 URL
        assertTrue(error("么嗲example：89media0") is ServerAddressNormalizer.Error.InvalidHost)
        assertTrue(error("media.example：8920") is ServerAddressNormalizer.Error.InvalidHost)
    }

    // ---- 显式 scheme 识别（UI 开关同步用） ----

    @Test
    fun `explicit http scheme detection`() {
        assertEquals("https", ServerAddressNormalizer.explicitHttpScheme("https://media.example:8920"))
        assertEquals("http", ServerAddressNormalizer.explicitHttpScheme("HTTP://media.example"))
        assertEquals("https", ServerAddressNormalizer.explicitHttpScheme("  https://media.example "))
        assertNull(ServerAddressNormalizer.explicitHttpScheme("media.example:8920"))
        assertNull(ServerAddressNormalizer.explicitHttpScheme("ftp://media.example"))
        assertNull(ServerAddressNormalizer.explicitHttpScheme(""))
    }

    // ---- withScheme（开关手动切换重写文本协议） ----

    @Test
    fun `withScheme rewrites scheme keeping port and path`() {
        assertEquals("http://media.example:8920/jellyfin", ServerAddressNormalizer.withScheme("https://media.example:8920/jellyfin", "http"))
        assertEquals("https://media.example:8920/jellyfin", ServerAddressNormalizer.withScheme("http://media.example:8920/jellyfin", "https"))
    }

    @Test
    fun `withScheme on bare input just prepends`() {
        assertEquals("https://media.example:8920", ServerAddressNormalizer.withScheme("media.example:8920", "https"))
    }

    @Test
    fun `withScheme roundtrip preserves everything but scheme`() {
        val raw = "HTTPS://Media.Example:8920/JeLLyFin%2Fx"
        val rewritten = ServerAddressNormalizer.withScheme(raw, "http")
        assertEquals("http://Media.Example:8920/JeLLyFin%2Fx", rewritten)
        val a = ok(rewritten, preferHttps = false)
        assertEquals("http://Media.Example:8920/JeLLyFin%2Fx", a.url)
    }
}
