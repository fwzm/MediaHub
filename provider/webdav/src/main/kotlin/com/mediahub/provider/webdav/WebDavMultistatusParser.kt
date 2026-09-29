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
        private var pendingCollection = false

        /** response 级 status（非 propstat）：出现即按其成败决定整条 response 去留。 */
        private var responseLevelStatusSeen = false
        private var responseLevelOk = false

        private val okProps = mutableMapOf<String, String>()
        private val pendingProps = mutableMapOf<String, String>()
        private var text: StringBuilder? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (uri != DAV_NS) return
            when (localName) {
                "response" -> reset()
                "propstat" -> {
                    inPropstat = true
                    propstatOk = false
                    pendingProps.clear()
                    pendingCollection = false
                }
                "status" -> text = StringBuilder()
                "collection" -> {
                    // collection 归属当前 propstat；只有成功 propstat 的才会计入
                    if (inPropstat) pendingCollection = true
                }
                "href",
                "displayname",
                "getcontentlength",
                "getcontenttype",
                "getlastmodified",
                "getetag",
                -> text = StringBuilder()
                else -> Unit
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            text?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (uri != DAV_NS) return
            val value = text?.toString()?.trim()
            when (localName) {
                "status" -> {
                    if (inPropstat) {
                        // 只有 2xx 的 propstat 才是有效属性来源（按状态行解析取码）
                        propstatOk = isSuccess(value)
                    } else {
                        // response 级 status：非 2xx → 整条丢弃；未出现 → 不影响
                        responseLevelStatusSeen = true
                        responseLevelOk = isSuccess(value)
                    }
                    text = null
                }
                "propstat" -> {
                    if (propstatOk) {
                        okProps.putAll(pendingProps)
                        isCollection = isCollection || pendingCollection
                    }
                    pendingProps.clear()
                    pendingCollection = false
                    inPropstat = false
                    propstatOk = false
                }
                "href" -> {
                    href = value
                    text = null
                }
                "displayname", "getcontentlength", "getcontenttype", "getlastmodified", "getetag" -> {
                    text = null
                    val key = localName ?: return
                    val v = value ?: return
                    if (!inPropstat) return
                    pendingProps[key] = v
                }
                "response" -> {
                    emit()
                    reset()
                }
                else -> Unit
            }
        }

        private fun emit() {
            if (responseLevelStatusSeen && !responseLevelOk) return
            val h = href ?: return
            if (h.isBlank()) return
            val length = okProps["getcontentlength"]?.toLongOrNull()
            result += WebDavResource(
                href = h,
                isCollection = isCollection,
                displayName = okProps["displayname"]?.takeIf { it.isNotBlank() },
                contentLength = length,
                contentType = okProps["getcontenttype"]?.takeIf { it.isNotBlank() },
                lastModified = okProps["getlastmodified"]?.takeIf { it.isNotBlank() },
                etag = okProps["getetag"]?.takeIf { it.isNotBlank() },
            )
        }

        private fun reset() {
            href = null
            isCollection = false
            inPropstat = false
            propstatOk = false
            pendingCollection = false
            responseLevelStatusSeen = false
            responseLevelOk = false
            okProps.clear()
            pendingProps.clear()
            text = null
        }
    }
}
