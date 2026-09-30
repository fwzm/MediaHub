package com.mediahub.core.logging

/**
 * 日志脱敏器。集中处理所有敏感信息的隐藏规则：
 * Authorization / Cookie / Token / 密码 / API Key 等。
 *
 * 规则：
 * - 按"键名 + 分隔符"模式识别（不误伤普通文本）。
 * - 请求头按键名识别。
 */
object Redactor {

    const val REDACTED = "****"

    private val HEADER_KEYS_SENSITIVE = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-emby-token",
        "x-mediabrowser-token",
        "x-plex-token",
        "x-api-key",
        "api-key",
        "apikey",
        "access_token",
        "refresh_token",
        "password",
        "token",
        "session_token",
        "session-key",
        "client_secret",
        "proxy-authorization",
    )

    /**
     * 单段输出上限（A2-4 第 3 项 fail-closed）：超过后先脱敏全文、再截断输出，
     * 防止超长输入拖垮 pattern 引擎；先脱敏后截断保证截断点切不断的半截秘密。
     */
    const val MAX_OUTPUT_LENGTH = 20_000

    private const val TRUNCATION_SUFFIX = "…[truncated]"

    /** JSON / 表单 / 查询串中的键值对，如 "access_token":"abc"、password=xyz、?token=abc */
    private val KEY_VALUE_PATTERNS = listOf(
        // JSON / 表单（带引号的值）
        Regex(
            """(?i)("?(?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)"?\s*[:=]\s*")([^",\s}]+)("?)"""
        ),
        // URL 查询串 / 表单（无引号的值）。
        // searchterm（Phase 1C-1）：用户搜索词属隐私，错误日志不得保留其值；
        // 仅抹值本身，StartIndex/Limit/IncludeItemTypes 等诊断参数不受影响。
        Regex(
            """(?i)([?&](?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)=)([^&\s"]+)"""
        ),
        // URL user-info（A2-4 第 3 项）：scheme://user:password@ → scheme://****@。
        // 三个捕获组对齐 redact() 的替换约定（组1 前缀 + **** + 组3 后缀），
        // 中间组（user:password）被丢弃；仅匹配带冒号密码的形态，
        // user@host（无密码）与 host:port 不受影响（值段不吃 '/'，host:8096 后随 '/' 无法满足尾部 @）。
        Regex("""(?i)((?:[a-zA-Z][a-zA-Z0-9+.\-]*://))([^/\s:@]+:[^/\s@]+)(@)"""),
        // 裸 key=value（A2-4 第 3 项）：异常文本等非查询串上下文，如 "password=xxx"。
        // 前置分隔符限定行首/空白/常见标点，避免误伤普通英文单词；键名保留、值替换。
        Regex(
            """(?i)((?:^|[\s,;("'])(?:password|passwd|pw|secret|token|api_key|apikey|access_token|refresh_token|authorization)=)([^\s"&',;)}\]]+)"""
        ),
        // 请求头（整行值）
        Regex("""(?i)(authorization\s*[:=]\s*)([^
]+)"""),
        Regex("""(?i)(cookie\s*[:=]\s*)([^
]+)"""),
        Regex("""(?i)(x-(?:emby|mediabrowser|plex)-token\s*[:=]\s*)([^
]+)"""),
    )

    /** 脱敏一段文本（URL / 响应体 / 异常消息）。超长输入先全文脱敏再截断（fail-closed）。 */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        for (pattern in KEY_VALUE_PATTERNS) {
            out = pattern.replace(out) { match ->
                val groups = match.groupValues
                val prefix = if (groups.size > 1) groups[1] else ""
                val suffix = if (groups.size > 3) groups[3] else ""
                prefix + REDACTED + suffix
            }
        }
        if (out.length > MAX_OUTPUT_LENGTH) {
            out = out.take(MAX_OUTPUT_LENGTH) + TRUNCATION_SUFFIX
        }
        return out
    }

    /** 脱敏请求/响应头（键名命中敏感集合时隐藏值）。 */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            if (key.lowercase() in HEADER_KEYS_SENSITIVE) REDACTED else value
        }

    /** 判断某个请求头键名是否敏感。 */
    fun isSensitiveHeader(key: String): Boolean = key.lowercase() in HEADER_KEYS_SENSITIVE
}
