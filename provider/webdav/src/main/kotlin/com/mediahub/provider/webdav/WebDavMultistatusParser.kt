package com.mediahub.provider.webdav

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * `multistatus` (RFC 4918) 解析器。
 *
 * 安全约束（A2-2 收紧）：
 * - **fail-closed 的 XXE 防线**：禁用 DTD 与外部实体是解析的前置条件——
 *   [SECURE_FEATURES] 任一设置失败即拒绝解析（[IllegalStateException]，
 *   由调用方包装为 `Parse`），绝不静默降级继续。响应大小上限（WebDavApi 8 MiB）
 *   只是纵深防御，**不能替代** DTD/外部实体阻断。
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
        val unsupported = mutableListOf<String>()
        SECURE_FEATURES.forEach { (feature, value) ->
            try {
                factory.setFeature(feature, value)
            } catch (ignored: Exception) {
                unsupported += feature
            }
        }
        if (unsupported.isNotEmpty()) {
            // fail-closed：无法建立 DTD/外部实体阻断边界时拒绝解析。
            throw IllegalStateException("XML 安全特性不可用，拒绝解析: $unsupported")
        }
        val handler = Handler()
        val parser = factory.newSAXParser()
        parser.parse(
            InputSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))),
            handler,
        )
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
