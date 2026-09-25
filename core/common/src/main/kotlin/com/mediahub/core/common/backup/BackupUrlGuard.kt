package com.mediahub.core.common.backup

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import com.mediahub.core.common.ServerAddressIdentity

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
        "x-emby-token", "x-emby-authorization", "apikeyheader", "cookie", "cookies",
        "accesstoken", "refresh_token", "refreshtoken", "playsessionid", "deviceid",
        "x-amz-credential", "x-amz-signature", "x-amz-security-token", "x-goog-credential", "x-goog-signature",
    )

    /** 校验失败原因。 */
    sealed interface Violation {
        data object UserInfo : Violation
        data class SensitiveQuery(val key: String) : Violation
    }

    /**
     * 校验单个 URL。返回 null 表示通过；返回 [Violation] 表示拒绝。
     * 结构不合法（无 scheme/host）同样拒绝——备份内只应存在 http/https 服务器地址。
     */
    fun inspect(url: String): Violation? {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return Violation.SensitiveQuery("(URL 格式)")
        if (uri.rawUserInfo != null) return Violation.UserInfo
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.port !in -1..65535) {
            return Violation.SensitiveQuery("(URL 格式)")
        }
        for (query in listOfNotNull(uri.rawQuery, uri.rawFragment)) {
            for (pair in query.split('&', ';')) {
                val key = runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8").lowercase(Locale.ROOT) }
                    .getOrElse { return Violation.SensitiveQuery("(URL 编码)") }
                if (key in SENSITIVE_QUERY_KEYS) return Violation.SensitiveQuery(key)
            }
        }
        return null
    }

    /** 批量校验：返回首个违规（含上下文标签便于用户定位是哪条线路）。 */
    fun inspectAll(urls: Map<String, String>): Violation? {
        for (url in urls.values) {
            inspect(url)?.let { return it }
        }
        return null
    }

    /**
     * 身份比较用的 URL 规范化：小写 scheme/host、去尾部 `/`、去 fragment 与空 query。
     * 保留端口与路径——同一服务器换端口视为不同来源。
     */
    fun normalizeForIdentity(url: String): String = ServerAddressIdentity.normalize(url)
}
