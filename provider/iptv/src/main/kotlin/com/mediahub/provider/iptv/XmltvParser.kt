package com.mediahub.provider.iptv

import com.mediahub.model.EpgProgram
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * XMLTV 电子节目单解析器。
 *
 * 安全约束：EPG 来自外部 URL，禁止任何 DTD / 外部实体解析（XXE）。
 * 这里同时做两层防护：
 * 1. 输入包含 `<!DOCTYPE` 直接拒绝；
 * 2. 解析器特性可用时关闭外部实体与 DTD 加载。
 *
 * 时间语义：XMLTV `start` / `stop` 形如 `20261114200000 +0800`；
 * 带偏移时按该偏移归一化为 epoch 毫秒，不带偏移时按 **UTC** 解释（确定性优先，
 * 不依赖设备时区）。无法解析时间的节目直接跳过，不伪造时间。
 */
internal object XmltvParser {

    private const val MAX_PROGRAMS = 200_000
    private const val TIME_PATTERN_UTC = "yyyyMMddHHmmss"
    private const val TIME_PATTERN_OFFSET = "yyyyMMddHHmmss Z"
    private val OFFSET = Regex("""[+-]\d{4}""")

    fun parse(xml: String): List<EpgProgram> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
            "XMLTV 不允许 DTD"
        }
        val document = runCatching {
            secureBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        }.getOrElse { throw IllegalArgumentException("XMLTV 解析失败", it) }

        val nodes = document.getElementsByTagName("programme")
        val programs = ArrayList<EpgProgram>(minOf(nodes.length, 4096))
        for (index in 0 until nodes.length) {
            if (programs.size >= MAX_PROGRAMS) break
            val element = nodes.item(index) as? Element ?: continue
            val channelId = element.getAttribute("channel").trim()
            if (channelId.isEmpty()) continue
            val start = parseTime(element.getAttribute("start")) ?: continue
            val stop = parseTime(element.getAttribute("stop")) ?: continue
            if (stop <= start) continue
            val title = childText(element, "title").ifBlank { "未命名节目" }
            programs += EpgProgram(
                channelId = channelId,
                title = title,
                startEpochMs = start,
                endEpochMs = stop,
                description = childText(element, "desc").takeIf { it.isNotBlank() },
                iconUrl = childElement(element, "icon")
                    ?.getAttribute("src")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        }
        return programs.sortedBy { it.startEpochMs }
    }

    /** 解析频道 id → 展示名（EPG 页面的频道标题）；缺失返回空表。 */
    fun parseChannelNames(xml: String): Map<String, String> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
            "XMLTV 不允许 DTD"
        }
        val document = runCatching {
            secureBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        }.getOrElse { throw IllegalArgumentException("XMLTV 解析失败", it) }
        val nodes = document.getElementsByTagName("channel")
        val names = LinkedHashMap<String, String>(nodes.length)
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val id = element.getAttribute("id").trim()
            if (id.isEmpty()) continue
            val display = childText(element, "display-name").trim()
            if (display.isNotEmpty()) names[id] = display
        }
        return names
    }

    private fun secureBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        // Android 与 JDK 支持的 SAX 特性不同：逐项尝试，失败不阻断基本解析。
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
    }

    private fun childText(parent: Element, tag: String): String =
        childElement(parent, tag)?.textContent?.trim().orEmpty()

    private fun childElement(parent: Element, tag: String): Element? {
        val children = parent.getElementsByTagName(tag)
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.parentNode === parent) return element
        }
        return null
    }

    private fun parseTime(raw: String): Long? {
        val parts = raw.trim().split(Regex("\\s+"))
        val digits = parts.firstOrNull()
            ?.takeIf { it.length == 14 && it.all(Char::isDigit) }
            ?: return null
        val offset = parts.getOrNull(1)?.takeIf { OFFSET.matches(it) }
        val pattern = if (offset != null) TIME_PATTERN_OFFSET else TIME_PATTERN_UTC
        val input = if (offset != null) "$digits $offset" else digits
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { format.parse(input)?.time }.getOrNull()
    }
}
