package com.mediahub.provider.webdav

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * WebDAV Basic 认证头构建（review P2-6）。
 *
 * RFC 7617：Basic 默认 ISO-8859-1，服务端可在 WWW-Authenticate 中声明 `charset="UTF-8"`。
 * [charsetFromChallenge] 解析该声明；[build] 按指定 charset 编码。
 */
internal object WebDavBasicAuth {

    fun build(username: String, password: String, charset: Charset = StandardCharsets.ISO_8859_1): String {
        val raw = "$username:$password".toByteArray(charset)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    /** 解析 WWW-Authenticate 中的 charset 参数（如 `Basic realm="x", charset="UTF-8"`）。 */
    fun charsetFromChallenge(wwwAuthenticate: String?): Charset {
        val match = CHARSET_PATTERN.find(wwwAuthenticate ?: "")
        return when (match?.groupValues?.get(1)?.uppercase()) {
            "UTF-8", "UTF8" -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
    }

    /** PROPFIND Depth:0 请求体（验证凭据的受保护操作）。 */
    fun propfindBody(): String = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:displayname/></D:prop></D:propfind>"

    private val CHARSET_PATTERN = Regex("""charset\s*=\s*"?([A-Za-z0-9_-]+)"?""", RegexOption.IGNORE_CASE)
}
