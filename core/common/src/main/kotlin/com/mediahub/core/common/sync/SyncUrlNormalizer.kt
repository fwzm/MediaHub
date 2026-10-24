package com.mediahub.core.common.sync

import java.net.URI
import java.net.URISyntaxException

/**
 * 同步层自己的地址规范化（ADR-S001 D4），刻意不与任何一端的实现共用。
 *
 * 两端原本归一化方式不同：Android 总是写端口（含 scheme 默认端口），Windows 省略默认端口。
 * 复用任一端都会让同一台服务器产生两个身份，于是合同自定一种规范形：
 * - scheme 与 host 小写；
 * - http/https 端口永远显式，输入没写时补 80/443；
 * - 路径去掉尾部斜杠（根路径变成空串），其余部分保持输入时的转义；
 * - query 与 fragment 丢弃（它们带的是会话参数，不是身份）；
 * - user info 从输出中剥离，因此 "https://alice:hunter2@host" 里的凭据永远不会成为身份的一部分，
 *   也不会被回显到消息里。
 *
 * 结果幂等：再规范化一次不变。不是本端能拨号的绝对地址（仅 http/https/smb/file）返回 null，
 * 而不是半成品字符串——调用方把"没有身份"读作"这条来源不能按地址匹配"。
 */
object SyncUrlNormalizer {

    private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** 只接受本端来源真的能携带的 scheme；集合之外的 scheme 拒绝而不是猜测。 */
    private val SUPPORTED_SCHEMES = setOf("http", "https", "smb", "file")

    /** 规范化地址；null 表示这不是一个可拨号的绝对地址。 */
    fun normalize(url: String?): String? {
        if (url == null || url.isBlank()) return null

        var text = url.trim()
        if (text.startsWith("//")) {
            // 协议相对写法（"//host/path"）是粘贴地址常见的形态。
            text = "http:" + text
        } else if (!EXPLICIT_SCHEME.containsMatchIn(text)) {
            // 裸主机名是最常被输入的形态，按 http 读可以让 "host:8096" 与 "http://host:8096" 成为同一个身份。
            // 不带 scheme 却含 @ 的值是粘贴进来的凭据（或 mailto: 这类非 URI），不是来源地址：
            // 直接拒绝，而不是围绕 @ 编造出一个 host。
            if (text.contains('@')) return null
            text = "http://" + text
        }

        val uri: URI = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.isAbsolute) return null

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null

        val isHttp = scheme == "http" || scheme == "https"
        val host = uri.host ?: ""

        // "file:///C:/media" 是合法的无 host 绝对地址；其它受支持的 scheme 必须给出 host。
        if (scheme != "file" && host.isEmpty()) return null

        val authority: String = when {
            isHttp -> {
                // 输入省略端口时在这里补全——与 Windows 客户端相反，这是有意的（D4）。
                val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
                if (port <= 0) return null
                host.lowercase() + ":" + port
            }
            uri.port > 0 -> {
                // smb/file 没有默认端口，因此不发明一个；输入显式写了就保留。
                host.lowercase() + ":" + uri.port
            }
            else -> host.lowercase()
        }

        // rawPath 保留输入隐含的转义，路径因此可以在不被手工重编码的前提下比较；"/" 等于没有路径。
        var path = uri.rawPath ?: ""
        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        if (path == "/") path = ""

        return scheme + "://" + authority + path
    }

    /** 与 normalize 同义，但把"是否可规范化"显式返回（对应 Windows 的 TryNormalize 习惯）。 */
    fun tryNormalize(url: String?): Pair<Boolean, String?> {
        val normalized = normalize(url)
        return (normalized != null) to normalized
    }
}
