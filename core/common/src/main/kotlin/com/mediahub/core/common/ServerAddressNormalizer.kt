package com.mediahub.core.common

import java.net.URI

/**
 * 服务器地址规范化（Phase 1I，集中实现——测试连接/登录/保存/线路质量测试必须共用
 * 同一份规范化结果，禁止各 ViewModel 自行字符串拼接）。
 *
 * 职责边界（仅用户输入层）：协议补全/识别、显式端口与反代子路径保留、非法输入
 * 请求前拒绝。Emby 的 `/emby` 前缀与 Jellyfin 反代子路径仍由各 Provider 的
 * EndpointResolver 管理，本类绝不添加 Provider API 路径。
 *
 * 规则：
 * - 显式 scheme（http/https，大小写不敏感）以输入为准并回传（供 UI 同步 HTTPS 开关）；
 *   无 scheme 时按 preferHttps 补全。其他 scheme（ftp:// 等）明确拒绝。
 * - 支持裸域名、host:port、IPv4、[IPv6]:port、带反代子路径（路径大小写与既有
 *   百分号编码原样保留，不重复编码——最终经 [URI] 字符串解析做语法校验）。
 * - 明确拒绝：空地址、非法/越界端口、非 http(s) scheme、user:password@host、
 *   服务器根地址携带 query/fragment（显式提示，不静默截断）。
 * - "https://https://…" 这类重复 scheme 无法通过主机/端口解析，按端口/主机无效拒绝，
 *   绝不产生双重协议输出。
 */
object ServerAddressNormalizer {

    private const val HTTP = "http"
    private const val HTTPS = "https"
    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /** 规范化结果。 */
    sealed interface Result {
        data class Ok(val address: NormalizedAddress) : Result
        data class Invalid(val error: Error) : Result
    }

    /** 字段级错误（用户可操作文案）。 */
    sealed interface Error {
        val userMessage: String

        data object Empty : Error {
            override val userMessage: String get() = "请输入服务器地址"
        }

        data class UnsupportedScheme(val scheme: String) : Error {
            override val userMessage: String =
                "不支持的协议 \"$scheme://\"，仅支持 http:// 或 https://"
        }

        data class InvalidPort(val raw: String) : Error {
            override val userMessage: String = "端口无效：\"$raw\""
        }

        data object InvalidHost : Error {
            override val userMessage: String = "主机名无效或为空"
        }

        data object UserInfoNotAllowed : Error {
            override val userMessage: String =
                "地址不支持携带用户名密码（user:password@host），请移除后重试"
        }

        data class QueryOrFragmentNotAllowed(val hasQuery: Boolean, val hasFragment: Boolean) : Error {
            override val userMessage: String =
                "服务器地址" +
                    (if (hasQuery) "不支持 query（?…）" else "") +
                    (if (hasQuery && hasFragment) " 与 " else "") +
                    (if (hasFragment) "不支持 fragment（#…）" else "") +
                    "，请移除后重试"
        }
    }

    /** 规范化后的地址组件。 */
    data class NormalizedAddress(
        /** 完整规范化 URL（scheme 小写；显式端口与子路径保留）。 */
        val url: String,
        /** "https" | "http" */
        val scheme: String,
        /** 主机名（IPv6 为带方括号形式，与 [URI.getHost] 一致）。 */
        val host: String,
        /** 显式端口；null = 协议默认端口。 */
        val port: Int?,
        /** 反代子路径（保留大小写与既有百分号编码）；根地址为空串。 */
        val path: String,
    )

    /**
     * @param rawInput 用户原始输入（可含显式 scheme）
     * @param preferHttps 输入无 scheme 时的补全协议
     */
    fun normalize(rawInput: String, preferHttps: Boolean): Result {
        val input = rawInput.trim()
        if (input.isEmpty()) return Result.Invalid(Error.Empty)

        val schemeMatch = SCHEME_REGEX.find(input)
        val scheme: String
        val rest: String
        if (schemeMatch != null) {
            scheme = schemeMatch.value.substringBefore("://").lowercase()
            if (scheme != HTTP && scheme != HTTPS) return Result.Invalid(Error.UnsupportedScheme(scheme))
            rest = input.substring(schemeMatch.value.length)
        } else {
            scheme = if (preferHttps) HTTPS else HTTP
            rest = input
        }
        if (rest.isEmpty()) return Result.Invalid(Error.InvalidHost)

        // query/fragment：服务器根地址首版不支持，显式提示（不静默截断）——
        // 检查整个剩余部分（含路径），"host/path?x=1" 同样拒绝
        val queryIdx = rest.indexOf('?') >= 0
        val fragmentIdx = rest.indexOf('#') >= 0
        if (queryIdx || fragmentIdx) {
            return Result.Invalid(Error.QueryOrFragmentNotAllowed(hasQuery = queryIdx, hasFragment = fragmentIdx))
        }

        val slashIdx = rest.indexOf('/')
        val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        var path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
        if (authority.isEmpty()) return Result.Invalid(Error.InvalidHost)
        if (authority.contains('@')) return Result.Invalid(Error.UserInfoNotAllowed)

        // host / port 拆分（IPv6 必须带方括号）
        val host: String
        val portRaw: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return Result.Invalid(Error.InvalidHost)
            host = authority.substring(0, close + 1)
            val after = authority.substring(close + 1)
            when {
                after.isEmpty() -> portRaw = null
                after.startsWith(":") -> portRaw = after.substring(1)
                else -> return Result.Invalid(Error.InvalidHost)
            }
        } else {
            val colon = authority.indexOf(':')
            if (colon < 0) {
                host = authority
                portRaw = null
            } else {
                host = authority.substring(0, colon)
                portRaw = authority.substring(colon + 1)
            }
        }
        if (host.isEmpty()) return Result.Invalid(Error.InvalidHost)
        // 主机名仅接受 ASCII（RFC 3986 reg-name；中文 IDN 须先转 punycode）——
        // 拒绝 CJK/全角字符，防止输入法串键或粘贴产生永远连不上的"合法"假地址
        if (host.any { it.code > 127 }) return Result.Invalid(Error.InvalidHost)

        val port: Int? = portRaw?.let {
            val n = it.toIntOrNull() ?: return Result.Invalid(Error.InvalidPort(it))
            if (n !in 1..65535) return Result.Invalid(Error.InvalidPort(it))
            n
        }

        // 与既有保存语义一致：仅去掉结尾的 "/"（保留路径大小写与既有百分号编码）
        path = path.trimEnd('/')

        val candidate = scheme + "://" + host + (port?.let { ":$it" } ?: "") + path
        // 语法校验（空格/非法字符等）；URI(String) 解析不重编码，保留既有百分号序列
        val uri = try {
            URI.create(candidate)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(Error.InvalidHost)
        } catch (e: NullPointerException) {
            return Result.Invalid(Error.InvalidHost)
        }

        return Result.Ok(
            NormalizedAddress(
                url = uri.toString(),
                scheme = scheme,
                host = uri.host ?: host,
                port = port,
                path = path,
            )
        )
    }

    /**
     * 识别输入中的显式 http/https scheme（大小写不敏感）。
     * 供 UI 实现"粘贴完整 URL 以粘贴内容为准，同步 HTTPS 开关"：
     * 返回 null = 无显式 scheme 或非 http(s)（后者由 [normalize] 报 UnsupportedScheme）。
     */
    fun explicitHttpScheme(rawInput: String): String? {
        val match = SCHEME_REGEX.find(rawInput.trim()) ?: return null
        val scheme = match.value.substringBefore("://").lowercase()
        return if (scheme == HTTP || scheme == HTTPS) scheme else null
    }

    /**
     * 把输入的 scheme 强制替换为 [scheme]（保留端口与子路径原样）。
     * 供 HTTPS 开关手动切换使用：粘贴进来的完整 URL 在用户手动切换时也要随开关变 scheme
     * （"粘贴内容为准"只作用于粘贴同步的那一刻，不锁定后续手动切换）。
     * 仅做文本层替换，不校验——调用方随后应经 [normalize] 得出权威结果。
     */
    fun withScheme(rawInput: String, scheme: String): String {
        val input = rawInput.trim()
        val match = SCHEME_REGEX.find(input)
        val rest = if (match != null) input.substring(match.value.length) else input
        return "$scheme://$rest"
    }
}
