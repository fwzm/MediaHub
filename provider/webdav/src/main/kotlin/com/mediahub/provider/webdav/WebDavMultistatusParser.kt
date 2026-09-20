package com.mediahub.provider.webdav

import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * `multistatus` (RFC 4918) 解析器。
 *
 * 安全约束：
 * - **禁用外部实体与 DTD**（XXE）。Android/JVM 上部分特性不受支持，逐个 try-catch
 *   后仍会通过 [javax.xml.parsers.SAXParserFactory] 的 `setFeature` 尽力收紧；
 *   无法收紧时解析仍受响应大小上限约束（见 [WebDavApi]）。
 * - **命名空间感知**：只认 `DAV:` 命名空间，不依赖服务器使用的前缀（`D:` / `d:` / `lp1:`）。
 * - 只采纳处于 `2xx` `propstat` 中的属性；`404 Not Found` propstat 的属性全部丢弃。
 * - 属性顺序无关：`prop` 与 `status` 在 `propstat` 内的先后顺序都不影响结果。
 */
internal object WebDavMultistatusParser {

    private const val DAV_NS = "DAV:"

    private val SECURE_FEATURES = mapOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    fun parse(xml: String): List<WebDavResource> {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        SECURE_FEATURES.forEach { (feature, value) ->
            try {
                factory.setFeature(feature, value)
            } catch (ignored: Exception) {
                // 该实现不支持此特性：继续（响应大小上限仍是兜底防线）。
            }
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
                }
                "status" -> text = StringBuilder()
                "collection" -> isCollection = true
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
                    // 只有 2xx 的 propstat 才是有效属性来源。
                    propstatOk = value?.contains("200") == true
                    text = null
                }
                "propstat" -> {
                    if (propstatOk) okProps.putAll(pendingProps)
                    pendingProps.clear()
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
            okProps.clear()
            pendingProps.clear()
            text = null
        }
    }
}
