package com.mediahub.provider.api

/**
 * Provider 层统一异常。UI 层捕获后展示 [message]（用户可读的固定文案），
 * 同时用 [code] 做结构化诊断；日志输出必须脱敏。
 *
 * A2-4 第 1 项：cause 在构造入口统一包装为 [SanitizedProviderCause]——
 * 原始异常的 message / cause 链可能携带 URL、Token、密码等敏感文本，
 * 不允许原样进入 crash report 与日志。各子类的结构化字段（statusCode/url 等）
 * 保持原样，供代码做结构化诊断（字段不再拼进 message）。
 *
 * **透传例外（联合候选裁决，A2-4×F-C1-1 冲突消解）**：[java.io.IOException]
 * 与取消异常**保持原实例透传，不消毒**——它们是传输层契约信号：
 * F-C1-1 分层回归要求 `ProviderException.Network` 的 cause 必须是
 * IOException 本体（OkHttp/Media3 失败契约），取消异常更是全项目的
 * 不可吞红线。其文本敏感性由日志层脱敏（Redactor + safeLogText）覆盖，
 * 而不是在这里破坏类型契约。
 */
sealed class ProviderException(
    val serverId: String,
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause?.let(::sanitizeCause)) {

    enum class ErrorCode {
        AUTH_REQUIRED,
        AUTH_FAILED,
        AUTH_EXPIRED,
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
        ProviderException(serverId, ErrorCode.NETWORK, "网络错误，请检查网络连接", cause)

    /** HTTP 错误。 */
    class Http(
        serverId: String,
        val statusCode: Int,
        val url: String,
        val method: String = "GET",
        val requestId: String? = null,
    ) : ProviderException(serverId, ErrorCode.HTTP, "服务器返回 $statusCode（$method）")

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
        ProviderException(serverId, ErrorCode.UNKNOWN, "未知错误", cause)
}

/**
 * cause 消毒入口：IOException 与 CancellationException 是传输/取消契约信号，
 * 原实例透传（见类 KDoc 的裁决说明）；其余 cause 包装为 [SanitizedProviderCause]。
 */
private fun sanitizeCause(cause: Throwable): Throwable = when (cause) {
    is java.io.IOException -> cause
    is kotlin.coroutines.cancellation.CancellationException -> cause
    else -> SanitizedProviderCause(cause)
}

/**
 * 内部 cause 包装（A2-4 第 1 项，private：不出模块、不进公共 API）。
 *
 * - message 为固定占位 + 原始异常类名（类名不含用户数据，定位必需）；
 * - cause 链递归消毒（原始链的每一层都可能带敏感文本）；
 * - stackTrace 转发原 cause 的帧（定位需要帧信息，不需要文本）。
 */
private class SanitizedProviderCause(original: Throwable) : Throwable(
    original.cause?.let(::sanitizeCause),
) {
    override val message: String = "sanitized: ${original.javaClass.name}"

    init {
        setStackTrace(original.stackTrace)
    }
}
