package com.mediahub.core.network

import com.mediahub.core.logging.Redactor

/**
 * 服务端 API 调用失败。url / message 在构造时即经 [Redactor.redact] 脱敏
 * （A2-4 第 2 项）——异常对象会被日志、crash report、[toLogString] 全文带出去，
 * 不能依赖调用方记得先脱敏。
 */
class ApiException(
    val statusCode: Int,
    url: String,
    val method: String,
    val requestId: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(
    message?.let(Redactor::redact) ?: "HTTP $statusCode $method ${Redactor.redact(url)}",
    cause,
) {
    /** 已脱敏的请求 URL（查询串中的 token/api_key 等值替换为 ****）。 */
    val url: String = Redactor.redact(url)

    fun toLogString(): String =
        "ApiException{requestId=$requestId, method=$method, status=$statusCode, url=$url}"
}
