package com.mediahub.provider.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `multistatus` 解析契约。
 *
 * 关注点：命名空间前缀无关、`propstat` 状态过滤、目录自身/子级判定、
 * 以及 XXE 与畸形响应不得产生可用条目。
 */
class WebDavMultistatusParserTest {

    @Test
    fun `parses collection and files with status filter`() {
        val xml = WebDavFixtures.multistatus(
            WebDavFixtures.collection("/dav/", "根目录"),
            WebDavFixtures.file("/dav/movie.mkv", length = 1024, displayName = "电影.mkv"),
        )
        val resources = WebDavMultistatusParser.parse(xml)

        assertEquals(2, resources.size)
        assertTrue(resources[0].isCollection)
        assertEquals("根目录", resources[0].displayName)
        assertFalse(resources[1].isCollection)
        assertEquals(1024L, resources[1].contentLength)
        assertEquals("电影.mkv", resources[1].displayName)
    }

    @Test
    fun `namespace prefix is irrelevant`() {
        val xml = WebDavFixtures.multistatus(WebDavFixtures.prefixedFile("/dav/a.mp4", 42L))
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals(1, resources.size)
        assertEquals(42L, resources[0].contentLength)
        assertFalse(resources[0].isCollection)
    }

    @Test
    fun `props inside non-200 propstat are discarded`() {
        val xml = WebDavFixtures.multistatus(
            WebDavFixtures.file("/dav/x.mkv", length = 999, displayName = "x.mkv", with404Propstat = true),
        )
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals(1, resources.size)
        // href 仍然有效（条目存在），但 404 propstat 的属性不得被采纳。
        assertNull(resources[0].contentLength)
        assertNull(resources[0].displayName)
    }

    @Test
    fun `status before prop is handled`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response>
                <D:href>/dav/late.mkv</D:href>
                <D:propstat>
                  <D:status>HTTP/1.1 200 OK</D:status>
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength>7</D:getcontentlength>
                  </D:prop>
                </D:propstat>
              </D:response>""",
        )
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals(1, resources.size)
        assertEquals(7L, resources[0].contentLength)
    }

    @Test
    fun `entry without href is skipped`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response><D:propstat><D:prop><D:resourcetype/></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""",
        )
        assertTrue(WebDavMultistatusParser.parse(xml).isEmpty())
    }

    @Test
    fun `external entity is never expanded`() {
        // DOCTYPE + 外部实体：必须被拒绝，或至少不得产出实体展开内容。
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///c:/windows/win.ini">]>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>&xxe;</D:href></D:response>
            </D:multistatus>"""
        val outcome = runCatching { WebDavMultistatusParser.parse(xxe) }
        val resources = outcome.getOrNull().orEmpty()
        val leaked = resources.any { r ->
            r.href.contains("[fonts]", ignoreCase = true) ||
                r.href.contains("for 16-bit app support", ignoreCase = true) ||
                r.href.startsWith("file:")
        }
        assertFalse("外部实体内容不得进入结果：$resources", leaked)
        if (resources.isEmpty()) assertTrue(outcome.isFailure || resources.isEmpty())
    }

    @Test
    fun `malformed xml fails instead of returning partial data`() {
        val outcome = runCatching {
            WebDavMultistatusParser.parse("<D:multistatus xmlns:D=\"DAV:\"><D:response>")
        }
        assertTrue("畸形 XML 必须失败：$outcome", outcome.isFailure)
    }

    @Test
    fun `empty multistatus yields no resources`() {
        assertTrue(WebDavMultistatusParser.parse(WebDavFixtures.multistatus()).isEmpty())
    }
}

/** URL / href 处理契约（中文、空格、编码、同 origin 约束、目录自比较）。 */
class WebDavUrlsTest {

    @Test
    fun `normalize base adds trailing slash only once`() {
        assertEquals("https://h/dav/", WebDavUrls.normalizeBase("https://h/dav"))
        assertEquals("https://h/dav/", WebDavUrls.normalizeBase("https://h/dav/"))
        assertEquals("https://h/dav/", WebDavUrls.normalizeBase("  https://h/dav  "))
    }

    @Test
    fun `absolute path href resolves into same origin`() {
        val resolved = WebDavUrls.resolveSameOrigin("https://h/dav/", "/dav/a%20b.mkv")
        assertEquals("https://h/dav/a%20b.mkv", resolved)
    }

    @Test
    fun `relative href resolves into same origin`() {
        val resolved = WebDavUrls.resolveSameOrigin("https://h/dav/sub/", "a.mkv")
        assertEquals("https://h/dav/sub/a.mkv", resolved)
    }

    @Test
    fun `cross origin href is refused`() {
        assertNull(WebDavUrls.resolveSameOrigin("https://h/dav/", "https://evil.example/x.mkv"))
        assertNull(WebDavUrls.resolveSameOrigin("https://h:443/dav/", "http://h/dav/x.mkv"))
    }

    @Test
    fun `href carrying user-info is refused`() {
        assertNull(
            WebDavUrls.resolveSameOrigin("https://h/dav/", "https://alice:pw@h/dav/x.mkv"),
        )
    }

    @Test
    fun `default port does not block same origin resolution`() {
        // 显式端口保留在解析结果里；origin 比较时才做默认端口归一化。
        assertEquals(
            "https://h:443/dav/a.mkv",
            WebDavUrls.resolveSameOrigin("https://h:443/dav/", "/dav/a.mkv"),
        )
        // 端口不同 → 不同 origin，必须拒绝（用绝对 href 才能真正换 origin）。
        assertNull(WebDavUrls.resolveSameOrigin("https://h:8443/dav/", "https://h:9443/dav/a.mkv"))
    }

    @Test
    fun `unencoded cjk and space hrefs still resolve`() {
        // 部分服务器不按 RFC 4918 编码 href；这类地址必须仍能解析（否则整目录条目被丢弃）。
        val resolved = WebDavUrls.resolveSameOrigin("https://h/dav/", "/dav/我的 电影.mkv")
        assertEquals("https://h/dav/%E6%88%91%E7%9A%84%20%E7%94%B5%E5%BD%B1.mkv", resolved)
    }

    @Test
    fun `canonical compare ignores trailing slash query and case`() {
        val a = WebDavUrls.canonicalForCompare("https://H/dav/Sub/")
        val b = WebDavUrls.canonicalForCompare("https://h/dav/sub?x=1")
        assertEquals(a, b)
    }

    @Test
    fun `display name decodes cjk space and percent and keeps plus literal`() {
        assertEquals("我的 电影.mkv", WebDavUrls.displayNameOf("/dav/%E6%88%91%E7%9A%84%20%E7%94%B5%E5%BD%B1.mkv"))
        // `+` 在 URI path 中是字面量，不能被当成空格（URLDecoder 的 form 语义会破坏它）。
        assertEquals("a+b.mkv", WebDavUrls.displayNameOf("/dav/a+b.mkv"))
        assertEquals("100%.mkv", WebDavUrls.displayNameOf("/dav/100%25.mkv"))
    }

    @Test
    fun `last segment is the display name and root has none`() {
        assertEquals("dav", WebDavUrls.displayNameOf("/dav/"))
        assertEquals("", WebDavUrls.displayNameOf("/"))
    }

    @Test
    fun `media type and container mapping`() {
        assertEquals(com.mediahub.model.MediaType.FOLDER, WebDavUrls.mediaTypeOf("目录", true))
        assertEquals(com.mediahub.model.MediaType.VIDEO, WebDavUrls.mediaTypeOf("a.mkv", false))
        assertEquals(com.mediahub.model.MediaType.AUDIO, WebDavUrls.mediaTypeOf("a.flac", false))
        assertEquals(com.mediahub.model.MediaType.OTHER, WebDavUrls.mediaTypeOf("notes.txt", false))
        assertEquals("mkv", WebDavUrls.containerOf("a.mkv"))
        assertNull(WebDavUrls.containerOf("noext"))
    }
}
