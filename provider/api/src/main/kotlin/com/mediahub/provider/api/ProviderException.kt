package com.mediahub.provider.api

/**
 * Provider 层统一异常。UI 层捕获后展示 [message]（用户可读），
 * 同时用 [code] 做结构化诊断；日志输出必须脱敏。
 */
sealed class ProviderException(
    val serverId: String,
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class ErrorCode {
        AUTH_REQUIRED,
        AUTH_FAILED,
        AUTH_EXPIRED,
        /** 本地目录/文档树授权缺失或已失效，需要用户重新授权（SAF，ADR-020）。 */
        REAUTH_REQUIRED,
        NETWORK,
        HTTP,
        PARSE,
        NOT_FOUND,
        RATE_LIMITED,
        CONNECTION,
        NOT_IMPLEMENTED,
        UNKNOWN,
    }

    /** 需要登录。 */
    class AuthRequired(serverId: String) :
        ProviderException(serverId, ErrorCode.AUTH_REQUIRED, "需要登录：$serverId")

    /** 登录失败。 */
    class AuthFailed(serverId: String, reason: String? = null, cause: Throwable? = null) :
        ProviderException(
            serverId,
            ErrorCode.AUTH_FAILED,
            "登录失败${reason?.let { "：$it" }.orEmpty()}",
            cause,
        )

    /** 会话过期（Token 失效）。 */
    class AuthExpired(serverId: String) :
        ProviderException(serverId, ErrorCode.AUTH_EXPIRED, "登录状态已过期，请重新登录")

    /** 网络错误。 */
    class Network(serverId: String, cause: Throwable? = null) :
        ProviderException(serverId, ErrorCode.NETWORK, "网络错误：${cause?.message.orEmpty()}", cause)

    /** HTTP 错误。 */
    class Http(
        serverId: String,
        val statusCode: Int,
        val url: String,
        val method: String = "GET",
        val requestId: String? = null,
    ) : ProviderException(serverId, ErrorCode.HTTP, "服务器返回 $statusCode（$method $url）")

    /** 解析错误。 */
    class Parse(serverId: String, cause: Throwable? = null) :
        ProviderException(serverId, ErrorCode.PARSE, "数据解析失败", cause)

    /** 资源不存在。 */
    class NotFound(serverId: String, what: String) :
        ProviderException(serverId, ErrorCode.NOT_FOUND, "未找到：$what")

    /** 限流。 */
    class RateLimited(serverId: String, val retryAfterMs: Long? = null) :
        ProviderException(serverId, ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试")

    /** 连接失败（探测）。 */
    class Connection(serverId: String, message: String, cause: Throwable? = null) :
        ProviderException(serverId, ErrorCode.CONNECTION, message, cause)

    /**
     * 当前数据源暂不支持该操作（骨架阶段占位，见 TASKS.md / HANDOFF.md）。
     * 该异常是唯一的"未实现"通道，禁止散落 TODO/NotImplementedError。
     */
    class NotYetImplemented(serverId: String, scope: String) :
        ProviderException(serverId, ErrorCode.NOT_IMPLEMENTED, "$scope 尚未实现（骨架阶段）")

    /** 未知错误。 */
    class Unknown(serverId: String, cause: Throwable? = null) :
        ProviderException(serverId, ErrorCode.UNKNOWN, "未知错误：${cause?.message.orEmpty()}", cause)
}
