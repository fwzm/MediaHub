package com.mediahub.provider.webdav

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.XMLFilterImpl

/** Exercises Android's missing Apache features while retaining mandatory portable guards. */
class WebDavParserPlatformCompatibilityTest {
    @After fun restore() { WebDavMultistatusParser.saxFactoryProvider = { SAXParserFactory.newInstance() } }

    private fun androidLikeFactory(readerTransform: (XMLReader) -> XMLReader = { it }): SAXParserFactory {
        val delegate = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        return object : SAXParserFactory() {
            override fun setFeature(name: String, value: Boolean) {
                if (name.startsWith("http://apache.org/")) throw SAXNotRecognizedException(name)
                delegate.setFeature(name, value)
            }
            override fun getFeature(name: String) = delegate.getFeature(name)
            override fun newSAXParser(): SAXParser {
                val parser = delegate.newSAXParser()
                val reader = readerTransform(parser.xmlReader)
                return object : SAXParser() {
                    @Suppress("DEPRECATION") override fun getParser() = parser.parser
                    override fun getXMLReader() = reader
                    override fun isNamespaceAware() = true
                    override fun isValidating() = false
                    override fun setProperty(name: String, value: Any?) = reader.setProperty(name, value)
                    override fun getProperty(name: String) = reader.getProperty(name)
                }
            }
        }
    }

    private val normal = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/a%20b.mkv</d:href><d:propstat><d:prop><d:displayname>影片 &amp; file</d:displayname><d:getcontentlength>17</d:getcontentlength></d:prop><d:status>HTTP/1.1 207 Multi-Status</d:status></d:propstat></d:response></d:multistatus>"""

    @Test fun `normal response works when Apache-only features are unsupported`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory() }
        val result = WebDavMultistatusParser.parse(normal)
        assertEquals(1, result.size)
        assertEquals("/dav/a%20b.mkv", result.single().href)
        assertEquals("影片 & file", result.single().displayName)
        assertEquals(17L, result.single().contentLength)
    }

    @Test fun `portable guards reject DTD and expansion without Apache features`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory() }
        listOf("<!DOCTYPE d:multistatus>",
            "<!DOCTYPE d:multistatus [<!ENTITY a 'aaaaaaaaaa'><!ENTITY b '&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;'><!ENTITY c '&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;'>]>")
            .forEach { assertTrue(runCatching { WebDavMultistatusParser.parse(it + normal) }.isFailure) }
    }

    @Test fun `external DTD and parameter and general entities make zero TCP connections`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory() }
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 200
            val url = "http://127.0.0.1:${server.localPort}/external"
            listOf("<!DOCTYPE d:multistatus SYSTEM '$url'>",
                "<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM '$url'>]>",
                "<!DOCTYPE d:multistatus [<!ENTITY % x SYSTEM '$url'>%x;]>")
                .forEach { assertTrue(runCatching { WebDavMultistatusParser.parse(it + normal) }.isFailure) }
            try { server.accept().use { }; throw AssertionError("external XML caused TCP access") }
            catch (_: SocketTimeoutException) { /* no connection, including immediately closed attempts */ }
        }
    }

    @Test fun `missing lexical guard refuses even normal response`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory { parent ->
            object : XMLFilterImpl(parent) {
                override fun setProperty(name: String, value: Any?) {
                    if (name == "http://xml.org/sax/properties/lexical-handler") throw SAXNotRecognizedException(name)
                    super.setProperty(name, value)
                }
            }
        } }
        assertTrue(runCatching { WebDavMultistatusParser.parse(normal) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `lying external feature readback refuses normal response`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory { parent ->
            object : XMLFilterImpl(parent) {
                override fun getFeature(name: String): Boolean =
                    if (name == "http://xml.org/sax/features/external-general-entities") true else super.getFeature(name)
            }
        } }
        assertTrue(runCatching { WebDavMultistatusParser.parse(normal) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `lying lexical registration readback refuses normal response`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory { parent ->
            object : XMLFilterImpl(parent) {
                override fun getProperty(name: String): Any? =
                    if (name == "http://xml.org/sax/properties/lexical-handler") null else super.getProperty(name)
            }
        } }
        assertTrue(runCatching { WebDavMultistatusParser.parse(normal) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `lying resolver registration readback refuses normal response`() {
        WebDavMultistatusParser.saxFactoryProvider = { androidLikeFactory { parent ->
            object : XMLFilterImpl(parent) {
                override fun getEntityResolver(): org.xml.sax.EntityResolver? = null
            }
        } }
        assertTrue(runCatching { WebDavMultistatusParser.parse(normal) }.exceptionOrNull() is IllegalStateException)
    }
}
