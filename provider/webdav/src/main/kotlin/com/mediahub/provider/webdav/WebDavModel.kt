package com.mediahub.provider.webdav

import com.mediahub.model.MediaType
import com.mediahub.model.MediaTypeGuesser

/**
 * 一条 PROPFIND 资源（RFC 4918 `response` 元素）。
 *
 * [href] 保留服务器原始形式（未解码）：RFC 3986 percent-encoding 由
 * [WebDavUrls.displayNameOf] / [WebDavUrls.percentDecode] 按需解码。
 */
internal data class WebDavResource(
    val href: String,
    val isCollection: Boolean,
    val displayName: String?,
    val contentLength: Long?,
    val contentType: String?,
    val lastModified: String?,
    val etag: String?,
)

/**
 * WebDAV URL 处理。
 *
 * 安全边界：只接受与服务器**同 origin**（scheme + host + 有效端口）的 href，
 * 且拒绝解析结果中带 user-info 的地址。跨 origin 或带凭据的 href 一律丢弃，
 * 避免 Basic 凭据被带到第三方主机（播放时 [PlaybackSource.headers][com.mediahub.model.PlaybackSource.headers]
 * 会随请求发出）。
 *
 * 兼容性：RFC 4918 要求 href 是 URI（已编码），但实际服务器会返回**未编码**的
 * 中文/空格路径。这里对这类字符做一次编码后再解析，两个方向都要能工作。
 */
internal object WebDavUrls {

    private val ORIGIN_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://([^/?#]*)")

    /** 规范化 base：去尾部空白，保证以 `/` 结尾（PROPFIND 打在目录上）。 */
    fun normalizeBase(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** base 的 origin（`scheme://host:port`，端口按 scheme 默认值归一化）；无法解析返回 null。 */
    fun origin(url: String): String? {
        val match = ORIGIN_REGEX.find(url.trim()) ?: return null
        val scheme = match.groupValues[1].lowercase()
        var authority = match.groupValues[2]
        if (authority.isEmpty()) return null
        // 丢弃 user-info（`user:pass@host`）：身份只走 Authorization 头。
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.isEmpty()) return null
        val (host, port) = splitHostPort(authority)
        if (host.isEmpty()) return null
        val effectivePort = port ?: if (scheme == "https") 443 else 80
        return "$scheme://${host.lowercase()}:$effectivePort"
    }

    /**
     * 把服务器返回的 href 解析为绝对 URL。
     * 跨 origin、带 user-info、非 http(s)、或无法解析时返回 null —— 调用方必须丢弃该条目。
     */
    fun resolveSameOrigin(baseUrl: String, href: String): String? {
        val raw = href.trim()
        if (raw.isEmpty()) return null
        val base = baseUrl.trim()
        if (base.isEmpty()) return null

        val baseOrigin = origin(base) ?: return null
        val resolved = resolveTolerantly(base, raw) ?: return null
        val resolvedOrigin = origin(resolved) ?: return null
        if (!resolvedOrigin.startsWith("http://") && !resolvedOrigin.startsWith("https://")) return null
        if (!baseOrigin.equals(resolvedOrigin, ignoreCase = true)) return null
        if (authorityOf(resolved)?.contains('@') == true) return null
        return resolved
    }

    /**
     * 先按标准 URI 解析；失败（未编码的空格/中文）时对非法字符做一次编码后重试。
     * 保留 `java.net.URI` 的路径归一化（`.`/`..`）语义。
     */
    private fun resolveTolerantly(base: String, raw: String): String? {
        try {
            return java.net.URI(base).resolve(raw).toString()
        } catch (ignored: Exception) {
            // 回落到编码后重试。
        }
        return try {
            java.net.URI(base).resolve(encodeIllegalUrlChars(raw)).toString()
        } catch (ignored: Exception) {
            null
        }
    }

    /** 把在 URI 中非法的字符（空格、控制符、非 ASCII）编码为 `%XX`；不改动已有 `%`。 */
    private fun encodeIllegalUrlChars(value: String): String {
        val out = StringBuilder(value.length + 16)
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val legal = codePoint in 0x21..0x7E && codePoint != 0x25
            if (legal) {
                out.append(value, i, i + charCount)
            } else {
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    out.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
            i += charCount
        }
        return out.toString()
    }

    private fun authorityOf(url: String): String? =
        ORIGIN_REGEX.find(url.trim())?.groupValues?.get(2)

    private fun splitHostPort(authority: String): Pair<String, Int?> {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return authority to null
            val host = authority.substring(0, end + 1)
            val rest = authority.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val port = authority.substring(colon + 1).toIntOrNull() ?: return authority to null
        return authority.substring(0, colon) to port
    }

    /** 归一化用于"目录自身条目"比较：去 query/fragment，去尾部 `/`，小写。 */
    fun canonicalForCompare(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        return noQuery.trimEnd('/').lowercase()
    }

    /** 取 href 的最后一段作为显示名（percent-decoded）；无法判定时返回空串。 */
    fun displayNameOf(href: String): String {
        val path = href.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        return percentDecode(last)
    }

    /**
     * 仅解码 `%XX`，**不**把 `+` 当空格（`java.net.URLDecoder` 的 form-encoding
     * 语义不适用于 URI path，会把合法文件名里的 `+` 破坏掉）。
     */
    fun percentDecode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = StringBuilder(value.length)
        val bytes = java.io.ByteArrayOutputStream(4)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hexEnd = i + 2
            if (c == '%' && hexEnd < value.length) {
                val decoded = value.substring(i + 1, hexEnd + 1).toIntOrNull(16)
                if (decoded != null) {
                    // 连续 %XX 组成多字节 UTF-8 序列，需一起解码（中文/emoji 名）。
                    bytes.write(decoded)
                    i = hexEnd + 1
                    continue
                }
            }
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
            out.append(c)
            i++
        }
        if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    /** 由显示名推断媒体类型；未知扩展名回落 OTHER（与 Local Provider 同规则）。 */
    fun mediaTypeOf(displayName: String, isCollection: Boolean): MediaType =
        if (isCollection) MediaType.FOLDER else MediaTypeGuesser.forPath(displayName)

    /** 扩展名（小写，无点）；无扩展名返回 null。 */
    fun containerOf(displayName: String): String? {
        if (!displayName.contains('.')) return null
        val ext = displayName.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotBlank() }
    }
}
