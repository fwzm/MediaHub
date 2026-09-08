package com.mediahub.core.common.backup

/**
 * 备份 URL 值级守卫（Phase 1I review：DTO 无 Token 字段不代表字段值中不夹带凭据）。
 *
 * 导出与导入双向校验服务器/线路 URL：
 * - user-info 形式（`scheme://user:pass@host`）可能携带明文凭据；
 * - 敏感 query 参数（token/api_key/sign 等）可能携带会话凭据；
 * 命中即拒绝（导出报错给用户、导入判 Corrupted），不做静默剥离。
 */
object BackupUrlGuard {

    /** 已知携带凭据语义的 query 参数名（小写比较）。 */
    val SENSITIVE_QUERY_KEYS = setOf(
        "token", "access_token", "api_key", "apikey", "api-key",
        "auth", "authorization", "password", "passwd", "pwd",
        "secret", "signature", "sign", "sessionid", "session_id",
        "x-emby-token", "x-emby-authorization", "apikeyheader",
    )

    /** 校验失败原因。 */
    sealed interface Violation {
        data class UserInfo(val rawUrl: String) : Violation
        data class SensitiveQuery(val rawUrl: String, val key: String) : Violation
    }

    /**
     * 校验单个 URL。返回 null 表示通过；返回 [Violation] 表示拒绝。
     * 结构不合法（无 scheme/host）同样拒绝——备份内只应存在 http/https 服务器地址。
     */
    fun inspect(url: String): Violation? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return Violation.SensitiveQuery(trimmed, "(scheme)") // 无法解析 → 拒绝
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return Violation.SensitiveQuery(trimmed, "(scheme)")

        val authorityAndRest = trimmed.substring(schemeEnd + 3)
        val authority = authorityAndRest.substringBefore('/', authorityAndRest).substringBefore('?')

        if (authority.contains('@')) return Violation.UserInfo(trimmed)

        val query = authorityAndRest.substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=').lowercase()
                if (key in SENSITIVE_QUERY_KEYS) return Violation.SensitiveQuery(trimmed, key)
            }
        }
        return null
    }

    /** 批量校验：返回首个违规（含上下文标签便于用户定位是哪条线路）。 */
    fun inspectAll(urls: Map<String, String>): Violation? {
        for ((label, url) in urls) {
            inspect(url)?.let { return it }
        }
        return null
    }

    /**
     * 身份比较用的 URL 规范化：小写 scheme/host、去尾部 `/`、去 fragment 与空 query。
     * 保留端口与路径——同一服务器换端口视为不同来源。
     */
    fun normalizeForIdentity(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return trimmed.lowercase()
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3).substringBefore('#')
        val hostPart = rest.substringBefore('/').lowercase()
        val pathPart = rest.substringAfter('/', "")
        return if (pathPart.isEmpty()) "$scheme://$hostPart" else "$scheme://$hostPart/$pathPart"
    }
}
