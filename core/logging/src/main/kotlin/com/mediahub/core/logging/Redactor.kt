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

    /** JSON / 表单 / 查询串中的键值对，如 "access_token":"abc"、password=xyz、?token=abc */
    private val KEY_VALUE_PATTERNS = listOf(
        // URL authority 中的 userinfo（username:password@host）；整段凭据都隐藏，含 percent-encoding。
        Regex("""(?i)((?:https?|wss?)://)([^/\s@]+)@"""),
        // JSON / 表单（带引号的值）。值允许空白、逗号与 escaped quote；
        // 必须消费到真正的结束引号，避免只隐藏第一个 token 后泄露剩余明文。
        Regex(
            """(?i)("?(?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)"?\s*[:=]\s*")((?:\\.|[^"\\])*)(")"""
        ),
        // 截断/畸形输入可能没有结束引号。识别到敏感 key 后必须 fail-closed，
        // 将直到输入末尾（包括末尾孤立反斜杠）的内容全部隐藏。
        Regex(
            """(?i)("?(?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)"?\s*[:=]\s*")((?:\\.|[^"\\])*(?:\\)?)\z"""
        ),
        // URL 查询串 / 表单（无引号的值）。
        // searchterm（Phase 1C-1）：用户搜索词属隐私，错误日志不得保留其值；
        // 仅抹值本身，StartIndex/Limit/IncludeItemTypes 等诊断参数不受影响。
        Regex(
            """(?i)([?&](?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)=)([^&\s"]+)"""
        ),
        // 裸表单/异常文本中的 key=value / key: value（不要求前面一定有 ? 或 &）。
        Regex(
            """(?i)(\b(?:access_token|refresh_token|id_token|token|api_key|apikey|api-key|password|passwd|pw|session_token|session_key|client_secret|secret|searchterm)\s*[:=]\s*)([^&\s",}]+)"""
        ),
        // 请求头（整行值）
        Regex("""(?i)(authorization\s*[:=]\s*)([^
]+)"""),
        Regex("""(?i)(cookie\s*[:=]\s*)([^
]+)"""),
        Regex("""(?i)(x-(?:emby|mediabrowser|plex)-token\s*[:=]\s*)([^
]+)"""),
    )

    /** 脱敏一段文本（URL / 响应体 / 异常消息）。 */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        for (pattern in KEY_VALUE_PATTERNS) {
            out = pattern.replace(out) { match ->
                val groups = match.groupValues
                val prefix = if (groups.size > 1) groups[1] else ""
                val suffix = when {
                    groups.size > 3 -> groups[3]
                    groups.size > 2 && groups[1].endsWith("://") -> "@"
                    else -> ""
                }
                prefix + REDACTED + suffix
            }
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
