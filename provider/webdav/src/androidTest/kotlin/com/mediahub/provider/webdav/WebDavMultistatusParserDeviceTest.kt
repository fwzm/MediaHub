package com.mediahub.provider.webdav

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Android runtime must accept ordinary DAV XML and reject DTD/entities with zero I/O.
 * Apache-only feature probes are diagnostic, never an excuse to skip happy-path assertions.
 */
@RunWith(AndroidJUnit4::class)
class WebDavMultistatusParserDeviceTest {

    // ---------------------------------------------------------------------
    // 平台能力探测
    // ---------------------------------------------------------------------

    /** Diagnostic legacy feature availability; positive parse assertions never depend on this. */
    private data class FeatureProbe(val feature: String, val supported: Boolean)

    @Test
    fun platformSaxFeatureCapabilityDeterminesParserPath() {
        val probes = platformProbes()
        val summary = probes.joinToString(", ") { probe ->
            "${probe.feature}=${if (probe.supported) "OK" else "UNSUPPORTED"}"
        }
        val api = Build.VERSION.SDK_INT
        val factoryImpl = SAXParserFactory.newInstance().javaClass.name
        Log.i(TAG, "API $api SAXParserFactory=$factoryImpl secure-feature probe: $summary")
        println("[$TAG] API $api SAXParserFactory=$factoryImpl secure-feature probe: $summary")

        val minimal = multistatus(
            """<lp1:response>
                 <lp1:href>/dav/ok/</lp1:href>
                 <lp1:propstat>
                   <lp1:prop><lp1:resourcetype><lp1:collection/></lp1:resourcetype></lp1:prop>
                   <lp1:status>HTTP/1.1 200 OK</lp1:status>
                 </lp1:propstat>
               </lp1:response>""",
        )
        val parsed = WebDavMultistatusParser.parse(minimal)
        assertEquals("正常 XML 必须在 Android 平台可解析，不能以 feature 缺失替代验收", 1, parsed.size)
        assertEquals("/dav/ok/", parsed[0].href)
        assertTrue("collection 语义必须照常解析", parsed[0].isCollection)
        Log.i(TAG, "API $api: mandatory portable guards installed, happy path verified")
    }

    // ---------------------------------------------------------------------
    // 正向：标准 207 multistatus
    // ---------------------------------------------------------------------

    @Test
    fun standard207WithI18nEncodingAndMixedPropstatOrdersParses() {
        // 覆盖：非 D 前缀（lp1:）namespace、中文 displayName、percent-encoded href、
        // status 在 prop 前（collection 条目）与 prop 后（file 条目）两种顺序、
        // file 条目两个 propstat 一成一败（quota propstat 失败不得污染）。
        val xml = multistatus(
            """<lp1:response>
                 <lp1:href>/dav/%E5%BD%B1%E7%89%87/</lp1:href>
                 <lp1:propstat>
                   <lp1:status>HTTP/1.1 200 OK</lp1:status>
                   <lp1:prop>
                     <lp1:displayname>影视目录</lp1:displayname>
                     <lp1:resourcetype><lp1:collection/></lp1:resourcetype>
                     <lp1:getlastmodified>Mon, 14 Sep 2026 08:00:00 GMT</lp1:getlastmodified>
                   </lp1:prop>
                 </lp1:propstat>
               </lp1:response>
               <lp1:response>
                 <lp1:href>/dav/%E5%BD%B1%E7%89%87/movie%20file.mkv</lp1:href>
                 <lp1:propstat>
                   <lp1:prop>
                     <lp1:displayname>movie file.mkv</lp1:displayname>
                     <lp1:getcontentlength>1048576</lp1:getcontentlength>
                     <lp1:getcontenttype>video/x-matroska</lp1:getcontenttype>
                     <lp1:getetag>&quot;etag-1a2b&quot;</lp1:getetag>
                   </lp1:prop>
                   <lp1:status>HTTP/1.1 207 Multi-Status</lp1:status>
                 </lp1:propstat>
                 <lp1:propstat>
                   <lp1:prop><lp1:quota-used-bytes>4096</lp1:quota-used-bytes></lp1:prop>
                   <lp1:status>HTTP/1.1 404 Not Found</lp1:status>
                 </lp1:propstat>
               </lp1:response>""",
        )
        parseOnDevice(xml, "standard207") { resources ->
            assertEquals("两条 response 都要产出条目", 2, resources.size)

            val folder = resources[0]
            assertEquals("href 必须保留服务器原始 percent-encoded 形式", "/dav/%E5%BD%B1%E7%89%87/", folder.href)
            assertTrue("resourcetype/collection 必须生效", folder.isCollection)
            assertEquals("中文 displayName 必须原样解析（UTF-8 字符流）", "影视目录", folder.displayName)
            assertEquals("Mon, 14 Sep 2026 08:00:00 GMT", folder.lastModified)
            assertNull("目录无 contentlength", folder.contentLength)

            val file = resources[1]
            assertEquals("/dav/%E5%BD%B1%E7%89%87/movie%20file.mkv", file.href)
            assertFalse("文件条目不得因失败 propstat 变成目录", file.isCollection)
            assertEquals("movie file.mkv", file.displayName)
            assertEquals(1048576L, file.contentLength)
            assertEquals("video/x-matroska", file.contentType)
            assertEquals("预定义实体 &quot; 必须被 SAX 解码", "\"etag-1a2b\"", file.etag)

            // href 编码形式的解码抽查（Android runtime UTF-8 多字节序列）。
            assertEquals("影片", WebDavUrls.percentDecode("%E5%BD%B1%E7%89%87"))
        }
    }

    // ---------------------------------------------------------------------
    // 安全：DOCTYPE / 外部实体零第二跳 / billion-laughs
    // ---------------------------------------------------------------------

    @Test
    fun doctypeDeclarationIsRejected() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE lp1:multistatus>
            <lp1:multistatus xmlns:lp1="DAV:">
              <lp1:response><lp1:href>/x</lp1:href></lp1:response>
            </lp1:multistatus>"""
        assertParseRejected(xml, "DOCTYPE 声明")
    }

    @Test
    fun externalEntityCausesZeroNetworkHops() {
        // 真实 ServerSocket 做零第二跳验证（不依赖 MockWebServer）：
        // 无论 Android SAX 在哪一层拒绝（disallow-doctype-decl 或 external-*-entities），
        // 都必须既解析失败、又不对 SYSTEM 指向的地址发起任何 TCP 连接。
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = ZERO_HOP_TIMEOUT_MS
            val port = server.localPort
            val xml = """<?xml version="1.0"?>
                <!DOCTYPE lp1:multistatus [
                  <!ENTITY xxe SYSTEM "http://127.0.0.1:$port/xxe">
                ]>
                <lp1:multistatus xmlns:lp1="DAV:">
                  <lp1:response><lp1:href>&xxe;</lp1:href></lp1:response>
                </lp1:multistatus>"""
            assertParseRejected(xml, "外部实体 SYSTEM（port=$port）")

            var connections = 0
            while (true) {
                try {
                    server.accept().close()
                    connections++
                } catch (expected: SocketTimeoutException) {
                    break
                }
            }
            assertEquals("外部实体不得发起任何第二跳连接（含半开/立即关闭的尝试）", 0, connections)
            Log.i(TAG, "XXE zero-second-hop verified on API ${Build.VERSION.SDK_INT}: connections=$connections port=$port")
        }
    }

    @Test
    fun billionLaughsInternalEntitiesRejected() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE lp1:multistatus [
              <!ENTITY lol "lol">
              <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
              <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
            ]>
            <lp1:multistatus xmlns:lp1="DAV:">
              <lp1:response><lp1:href>/&lol4;</lp1:href></lp1:response>
            </lp1:multistatus>"""
        assertParseRejected(xml, "billion-laughs 内部实体")
    }

    // ---------------------------------------------------------------------
    // 语义：失败 propstat / response 级状态 / 畸形状态行
    // ---------------------------------------------------------------------

    @Test
    fun failedPropstatCollectionDoesNotPollute() {
        val xml = multistatus(
            """<lp1:response>
                 <lp1:href>/dav/ghost/</lp1:href>
                 <lp1:propstat>
                   <lp1:prop><lp1:resourcetype><lp1:collection/></lp1:resourcetype></lp1:prop>
                   <lp1:status>HTTP/1.1 404 Not Found</lp1:status>
                 </lp1:propstat>
               </lp1:response>""",
        )
        parseOnDevice(xml, "failedPropstatCollection") { resources ->
            assertEquals("失败 propstat 只丢属性，href 仍有效（RFC 4918）", 1, resources.size)
            assertFalse("失败 propstat 里的 collection 不得把资源标成目录", resources[0].isCollection)
        }
    }

    @Test
    fun responseLevel404DiscardsWholeEntry() {
        val xml = multistatus(
            """<lp1:response>
                 <lp1:href>/dav/kept/</lp1:href>
                 <lp1:propstat>
                   <lp1:prop><lp1:resourcetype><lp1:collection/></lp1:resourcetype></lp1:prop>
                   <lp1:status>HTTP/1.1 200 OK</lp1:status>
                 </lp1:propstat>
               </lp1:response>
               <lp1:response>
                 <lp1:href>/dav/gone.mkv</lp1:href>
                 <lp1:status>HTTP/1.1 404 Not Found</lp1:status>
               </lp1:response>""",
        )
        parseOnDevice(xml, "responseLevel404") { resources ->
            assertEquals("response 级 404 的条目必须整体丢弃", 1, resources.size)
            assertEquals("/dav/kept/", resources[0].href)
            assertTrue(resources[0].isCollection)
        }
    }

    @Test
    fun malformedStatusLineTreatedAsFailure() {
        // 无 "HTTP/x.y <code>" 前缀的状态行无法取码 → 该 propstat 按失败处理。
        val xml = multistatus(
            """<lp1:response>
                 <lp1:href>/dav/badstatus.mkv</lp1:href>
                 <lp1:propstat>
                   <lp1:prop>
                     <lp1:displayname>badstatus.mkv</lp1:displayname>
                     <lp1:getcontentlength>42</lp1:getcontentlength>
                     <lp1:resourcetype><lp1:collection/></lp1:resourcetype>
                   </lp1:prop>
                   <lp1:status>Success</lp1:status>
                 </lp1:propstat>
               </lp1:response>""",
        )
        parseOnDevice(xml, "malformedStatusLine") { resources ->
            assertEquals("畸形状态行不丢 href（条目存在，属性不可信）", 1, resources.size)
            assertNull("畸形状态行的 propstat 属性不得采信", resources[0].displayName)
            assertNull(resources[0].contentLength)
            assertFalse("畸形状态行 propstat 里的 collection 不得生效", resources[0].isCollection)
        }
    }

    // ---------------------------------------------------------------------
    // 平台条件断言辅助
    // ---------------------------------------------------------------------

    private inline fun parseOnDevice(
        xml: String,
        label: String,
        assertions: (List<WebDavResource>) -> Unit,
    ) {
        assertions(WebDavMultistatusParser.parse(xml))
        Log.i(TAG, "$label: positive semantics verified on API ${Build.VERSION.SDK_INT}")
    }

    private fun assertParseRejected(xml: String, label: String) {
        try {
            WebDavMultistatusParser.parse(xml)
            fail("$label 必须被拒绝")
        } catch (rejected: Exception) {
            Log.i(TAG, "$label rejected: ${rejected.javaClass.simpleName}: ${rejected.message}")
        }
    }

    private fun multistatus(body: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
           <lp1:multistatus xmlns:lp1="DAV:">$body</lp1:multistatus>"""

    private fun platformProbes(): List<FeatureProbe> = platformProbesCached

    companion object {
        private const val TAG = "WebDavParserDeviceTest"
        private const val ZERO_HOP_TIMEOUT_MS = 2_000

        /** Legacy capability probes are retained to explain Android/JVM differences. */
        private val SECURE_FEATURE_PROBES = listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )

        /** 每进程探测一次（newInstance + 逐个 setFeature，与生产 parse 的时序一致）。 */
        private val platformProbesCached: List<FeatureProbe> by lazy {
            val factory = SAXParserFactory.newInstance()
            SECURE_FEATURE_PROBES.map { (feature, value) ->
                val supported = try {
                    factory.setFeature(feature, value)
                    true
                } catch (unsupported: Exception) {
                    Log.i(
                        TAG,
                        "setFeature failed on API ${Build.VERSION.SDK_INT}: $feature → " +
                            "${unsupported.javaClass.simpleName}: ${unsupported.message}",
                    )
                    false
                }
                FeatureProbe(feature, supported)
            }
        }
    }
}
