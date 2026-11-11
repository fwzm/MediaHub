package com.mediahub.provider.webdav

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.helpers.DefaultHandler

/**
 * `multistatus` (RFC 4918) 解析器。
 *
 * 安全约束（A2-2 收紧）：
 * - **fail-closed 的 XXE 防线**：实际 XMLReader 必须关闭两种外部实体并回读确认，
 *   安装且按引用回读确认 DTD lexical handler 与拒绝型 resolver。startDTD 在处理
 *   内/外部 subset 前抛异常；任何强制防线无法建立时拒绝解析。Android 不支持的
 *   Apache 专有 feature 仅是额外防护，不能代替这些可移植强制防线。
 *   响应大小上限（WebDavApi 8 MiB）只是纵深防御。
 * - **命名空间感知**：只认 `DAV:` 命名空间，不依赖服务器使用的前缀（`D:` / `d:` / `lp1:`）。
 *
 * 正确性契约（A2-2）：
 * - propstat 成败按**状态行解析**取状态码（`HTTP/1.1 207 Multi-Status` 是合法
 *   成功状态）；无法解析的状态行按失败处理（fail-closed）。
 * - `resourcetype/collection` 与其他属性一样**只接受所属成功 propstat 的值**；
 *   失败 propstat 里的 collection 不得把资源标成目录。
 * - **response 级** `<D:status>`（§9.1.2 的非 propstat 形态）非 2xx 时整条丢弃。
 * - 只有一个 propstat 的 response 其 href 仍然有效（条目存在，属性不可信）；
 *   属性顺序无关；多个 propstat 按 2xx 与否逐个合并。
 */
internal object WebDavMultistatusParser {

    private const val DAV_NS = "DAV:"

    private val SECURE_FEATURES = mapOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )
    private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"
    private val REQUIRED_READER_FEATURES = mapOf(
        "http://xml.org/sax/features/namespaces" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )

    /** `HTTP/1.1 207 Multi-Status` → 207。 */
    private val STATUS_LINE = Regex("""(?i)^HTTP/\S+\s+(\d{3})""")

    private fun statusCodeOf(status: String?): Int? =
        status?.trim()?.let { STATUS_LINE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun isSuccess(status: String?): Boolean {
        val code = statusCodeOf(status) ?: return false
        return code in 200..299
    }

    /** 测试接缝：允许测试注入会 setFeature 失败的 factory（open-for-test 约定）。 */
    internal var saxFactoryProvider: () -> SAXParserFactory = { SAXParserFactory.newInstance() }

    fun parse(xml: String): List<WebDavResource> {
        val factory = saxFactoryProvider()
        factory.isNamespaceAware = true
        SECURE_FEATURES.forEach { (feature, value) ->
            // Android Expat lacks Apache-only features. Mandatory reader guards below
            // are installed regardless of these additional factory-level features.
            runCatching { factory.setFeature(feature, value) }
        }
        val handler = Handler()
        val reader = factory.newSAXParser().xmlReader
        val guard = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("DOCTYPE is forbidden")
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw SAXException("External XML resolution is forbidden")
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
                throw SAXException("External XML resolution is forbidden")
            override fun getExternalSubset(name: String?, baseURI: String?): InputSource =
                throw SAXException("External XML subset is forbidden")
        }
        try {
            REQUIRED_READER_FEATURES.forEach { (feature, value) ->
                reader.setFeature(feature, value)
                check(reader.getFeature(feature) == value) { "XML reader feature readback failed: $feature" }
            }
            reader.setProperty(LEXICAL_HANDLER, guard)
            check(reader.getProperty(LEXICAL_HANDLER) === guard) { "XML DTD guard readback failed" }
            reader.entityResolver = guard
            check(reader.entityResolver === guard) { "XML resolver readback failed" }
        } catch (failure: Exception) {
            throw IllegalStateException("XML 强制安全防线不可用，拒绝解析", failure)
        }
        // XMLReader.parse preserves the guard; SAXParser.parse(DefaultHandler) would
        // overwrite entityResolver with the content handler.
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
        return handler.result
    }

    private class Handler : DefaultHandler() {
        val result = mutableListOf<WebDavResource>()
        private var href: String? = null
        private var isCollection = false
        private var inPropstat = false
        private var propstatOk = false
        private var successfulPropstat = false
        private var pendingCollection = false
        private var responseLevelStatusSeen = false
        private var responseLevelOk = false
        private val okProps = mutableMapOf<String, String>()
        private val pendingProps = mutableMapOf<String, String>()
        private var text: StringBuilder? = null

        // Only the six ancestry levels used by selected DAV properties are retained.
        private val path = ArrayList<Pair<String?, String?>>(6)
        private var depth = 0

        private fun at(expected: List<String>, leaf: String? = null): Boolean {
            val size = expected.size + if (leaf == null) 0 else 1
            if (depth != size || path.size != size) return false
            if (expected.indices.any { path[it].first != DAV_NS || path[it].second != expected[it] }) return false
            return leaf == null || (path.last().first == DAV_NS && path.last().second == leaf)
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (text != null) throw SAXException("Nested markup in a DAV scalar is not allowed")
            depth++
            if (depth <= 6) path.add(uri to localName)
            if (depth == 1 && !at(ROOT)) throw SAXException("Expected a DAV multistatus document root")
            when {
                at(RESPONSE) -> reset()
                at(PROPSTAT) -> {
                    inPropstat = true
                    propstatOk = false
                    pendingProps.clear()
                    pendingCollection = false
                }
                at(RESPONSE, "href") || at(RESPONSE, "status") || at(PROPSTAT, "status") -> text = StringBuilder()
                at(RESOURCE_TYPE, "collection") -> if (inPropstat) pendingCollection = true
                localName in SCALAR_PROPERTIES && at(PROPS, localName) -> text = StringBuilder()
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) { text?.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            try {
                val value = text?.toString()?.trim()
                when {
                    at(PROPSTAT, "status") -> { propstatOk = isSuccess(value); text = null }
                    at(RESPONSE, "status") -> {
                        responseLevelStatusSeen = true
                        responseLevelOk = isSuccess(value)
                        text = null
                    }
                    at(PROPSTAT) -> {
                        if (propstatOk) {
                            successfulPropstat = true
                            okProps.putAll(pendingProps)
                            isCollection = isCollection || pendingCollection
                        }
                        pendingProps.clear()
                        pendingCollection = false
                        inPropstat = false
                        propstatOk = false
                    }
                    at(RESPONSE, "href") -> { href = value; text = null }
                    localName in SCALAR_PROPERTIES && at(PROPS, localName) -> {
                        if (value != null && localName != null && inPropstat) pendingProps[localName] = value
                        text = null
                    }
                    at(RESPONSE) -> { emit(); reset() }
                }
            } finally {
                if (depth <= 6) path.removeAt(path.lastIndex)
                depth--
            }
        }

        private fun emit() {
            if (responseLevelStatusSeen && !responseLevelOk) return
            val h = href?.takeIf { it.isNotBlank() } ?: return
            result += WebDavResource(
                href = h,
                isCollection = isCollection,
                displayName = okProps["displayname"]?.takeIf { it.isNotBlank() },
                contentLength = okProps["getcontentlength"]?.toLongOrNull(),
                contentType = okProps["getcontenttype"],
                lastModified = okProps["getlastmodified"],
                etag = okProps["getetag"],
                hasSuccessfulStatus = if (responseLevelStatusSeen) responseLevelOk else successfulPropstat,
            )
        }

        private fun reset() {
            href = null
            isCollection = false
            inPropstat = false
            propstatOk = false
            successfulPropstat = false
            pendingCollection = false
            responseLevelStatusSeen = false
            responseLevelOk = false
            okProps.clear()
            pendingProps.clear()
            text = null
        }

        private companion object {
            val ROOT = listOf("multistatus")
            val RESPONSE = ROOT + "response"
            val PROPSTAT = RESPONSE + "propstat"
            val PROPS = PROPSTAT + "prop"
            val RESOURCE_TYPE = PROPS + "resourcetype"
            val SCALAR_PROPERTIES = setOf("displayname", "getcontentlength", "getcontenttype", "getlastmodified", "getetag")
        }
    }

}
