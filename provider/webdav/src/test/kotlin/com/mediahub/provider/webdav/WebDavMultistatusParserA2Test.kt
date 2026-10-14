package com.mediahub.provider.webdav

import javax.xml.parsers.SAXParserFactory
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2-2 parser 正确性与 XML 安全（fail-closed）回归。
 *
 * 修复点（先红后绿）：
 * - propstat 成功判定按**状态行解析**取码（`HTTP/1.1 207 Multi-Status` 是合法的
 *   成功状态，旧 `contains("200")` 会把 207 当失败丢弃全部属性）。
 * - `resourcetype/collection` 与其他属性一样**只属于成功的 propstat**；
 *   失败 propstat 里的 collection 不得把资源标成目录。
 * - **response 级** `<D:status>`（RFC 4918 §9.1.2 的非 propstat 形态）非 2xx 时
 *   整条 response 丢弃。
 * - 安全 feature 设置失败必须**拒绝解析**（fail-closed），不得静默继续；
 *   输入大小上限只是纵深防御，不能替代 DTD/外部实体阻断。
 */
class WebDavMultistatusParserA2Test {

    @Before
    fun setUp() {
        WebDavMultistatusParser.saxFactoryProvider = { SAXParserFactory.newInstance() }
    }

    @After
    fun tearDown() {
        WebDavMultistatusParser.saxFactoryProvider = { SAXParserFactory.newInstance() }
    }

    @Test
    fun `propstat status 207 multi-status counts as success`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response>
                <D:href>/dav/a.mkv</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype/><D:getcontentlength>77</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 207 Multi-Status</D:status>
                </D:propstat>
              </D:response>""",
        )
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals("207 是成功的 propstat 状态，属性必须被采纳", 1, resources.size)
        assertEquals(77L, resources[0].contentLength)
    }

    @Test
    fun `collection inside failed propstat does not make a folder`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response>
                <D:href>/dav/ghost/</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>""",
        )
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals("失败的 propstat 只丢属性，href 仍有效（RFC 4918）", 1, resources.size)
        assertFalse("失败 propstat 里的 collection 不得把资源标成目录", resources[0].isCollection)
    }

    @Test
    fun `response level failure status is discarded`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response>
                <D:href>/dav/gone.mkv</D:href>
                <D:status>HTTP/1.1 404 Not Found</D:status>
              </D:response>""",
        )
        assertTrue("response 级非 2xx 的条目必须整体丢弃", WebDavMultistatusParser.parse(xml).isEmpty())
    }

    @Test
    fun `multiple propstat merge only success values`() {
        val xml = WebDavFixtures.multistatus(
            """<D:response>
                <D:href>/dav/mix.mkv</D:href>
                <D:propstat>
                  <D:prop><D:getcontentlength>31</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:displayname>secret-name.mkv</D:displayname></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>""",
        )
        val resources = WebDavMultistatusParser.parse(xml)
        assertEquals(1, resources.size)
        assertEquals(31L, resources[0].contentLength)
        assertEquals("失败 propstat 的 displayname 不得混入", null, resources[0].displayName)
    }

    @Test
    fun `doctype is rejected outright`() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE D:multistatus>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>/x</D:href></D:response></D:multistatus>"""
        assertTrue("DOCTYPE 必须被拒绝", runCatching { WebDavMultistatusParser.parse(xml) }.isFailure)
    }

    @Test
    fun `internal entity expansion is rejected`() {
        // billion laughs 形态（内部实体）：DOCTYPE 阻断后必须直接失败
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE D:multistatus [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>&c;</D:href></D:response></D:multistatus>"""
        assertTrue("实体展开形态必须失败", runCatching { WebDavMultistatusParser.parse(xml) }.isFailure)
    }

    @Test
    fun `external entity resolution attempt causes no network access`() {
        val server = MockWebServer().apply { start() }
        try {
            val xml = """<?xml version="1.0"?>
                <!DOCTYPE D:multistatus [<!ENTITY xxe SYSTEM "http://127.0.0.1:${server.port}/xxe">]>
                <D:multistatus xmlns:D="DAV:"><D:response><D:href>&xxe;</D:href></D:response></D:multistatus>"""
            val outcome = runCatching { WebDavMultistatusParser.parse(xml) }
            assertTrue("外部实体解析必须失败", outcome.isFailure)
            assertEquals("不得发起任何第二跳访问", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `secure feature failure refuses parsing`() {
        // 模拟平台 SAX 实现"不支持安全 feature 但解析器本身可用"（真实危险形态）：
        // 此时拿到的解析器允许 DOCTYPE/外部实体，必须整体拒绝解析而不是继续。
        WebDavMultistatusParser.saxFactoryProvider = {
            val laxFactory = SAXParserFactory.newInstance() // 未设任何安全 feature
            object : SAXParserFactory() {
                override fun setFeature(name: String, value: Boolean) {
                    throw javax.xml.parsers.ParserConfigurationException("不支持: $name")
                }
                override fun getFeature(name: String): Boolean = false
                override fun newSAXParser() = laxFactory.newSAXParser()
            }
        }
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE D:multistatus>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>/x</D:href></D:response></D:multistatus>"""
        val outcome = runCatching { WebDavMultistatusParser.parse(xml) }
        assertTrue("安全边界建立失败必须拒绝解析：$outcome", outcome.isFailure)
    }
}
