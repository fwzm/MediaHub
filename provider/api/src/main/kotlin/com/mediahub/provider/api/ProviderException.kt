package com.mediahub.provider.api

import java.io.IOException

/**
 * Provider 层统一异常。UI 层捕获后展示 [message]（用户可读的固定文案），
 * 同时用 [code] 做结构化诊断；日志输出必须脱敏。
 *
 * cause 在构造入口统一消毒（A3-4 修订 A2 裁决）：
 * - **取消异常原实例透传**（全项目不可吞红线；其消息为协程机制文本，非用户数据）；
 * - **IOException 包装为 [SanitizedIOException]**——它 `is IOException`，
 *   F-C1-1 分层回归的类型契约（`ProviderException.Network.cause is IOException`）
 *   继续成立；消息为"原类名 (sanitized)"（保留超时/DNS 的诊断线索），
 *   原始文本（可能内嵌 URL/代理凭据）不进入 message、cause 链、suppressed
 *   与标准渲染（printStackTrace/stackTraceToString/toString）。
 *   子类细分（SocketTimeoutException 等）的分类发生在**catch 点的原始异常上**
 *   （wrap 之前），已核对无 main 源码在 ProviderException.cause 上做子类判断；
 * - 其余 cause 包装为 [SanitizedProviderCause]。
 *
 * 各子类的结构化字段（statusCode/url 等）保持原样供结构化诊断（url 已在
 * 构造时过 Redactor）。共享的原始异常对象**不被修改**——消毒产物是新建对象。
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
 * cause 消毒入口（A3-4 修订）：取消异常原实例透传（不可吞红线）；
 * IOException 包装为保持类型的 [SanitizedIOException]（F-C1-1 `is IOException`
 * 契约成立，文本不泄漏）；其余 cause 包装为 [SanitizedProviderCause]。
 */
private fun sanitizeCause(cause: Throwable): Throwable = when (cause) {
    is kotlin.coroutines.cancellation.CancellationException -> cause
    is java.io.IOException -> SanitizedIOException(cause)
    else -> SanitizedProviderCause(cause)
}

private fun sanitizeSuppressed(original: Throwable): Throwable = when (original) {
    is IOException -> SanitizedIOException(original)
    is kotlin.coroutines.cancellation.CancellationException -> original
    else -> SanitizedProviderCause(original)
}

/**
 * IOException 的类型保持消毒视图（private）。`is IOException` 成立（F-C1-1），
 * 消息为"原类名 (sanitized)"（保留超时/DNS 诊断线索、不携带原始文本）；
 * cause 链与 suppressed 递归消毒；stackTrace 转发原帧。不修改原对象。
 */
private class SanitizedIOException(original: IOException) : IOException(
    "${original.javaClass.simpleName} (sanitized)",
    original.cause?.let(::sanitizeCause),
) {
    init {
        setStackTrace(original.stackTrace)
        for (sup in original.suppressedExceptions) addSuppressed(sanitizeSuppressed(sup))
    }
}

/**
 * 内部 cause 包装（private：不出模块、不进公共 API）。
 *
 * - message 为固定占位 + 原始异常类名（类名不含用户数据，定位必需）；
 * - cause 链递归消毒；suppressed 递归消毒；
 * - stackTrace 转发原 cause 的帧（定位需要帧信息，不需要文本）。
 */
private class SanitizedProviderCause(original: Throwable) : Throwable(
    original.cause?.let(::sanitizeCause),
) {
    override val message: String = "sanitized: ${original.javaClass.name}"

    init {
        setStackTrace(original.stackTrace)
        for (sup in original.suppressedExceptions) addSuppressed(sanitizeSuppressed(sup))
    }
}
