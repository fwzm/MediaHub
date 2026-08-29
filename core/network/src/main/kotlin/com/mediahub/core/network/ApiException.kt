package com.mediahub.core.network

import com.mediahub.core.logging.Redactor

/** 服务端 API 调用失败（含可诊断信息，日志必须脱敏）。 */
class ApiException(
    val statusCode: Int,
    url: String,
    val method: String,
    val requestId: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message?.let(Redactor::redact) ?: "HTTP $statusCode $method ${Redactor.redact(url)}", cause) {

    /** 异常会被上层作为 throwable 记录，因此连结构化字段也不得保留原始敏感 query。 */
    val url: String = Redactor.redact(url)

    fun toLogString(): String =
        "ApiException{requestId=$requestId, method=$method, status=$statusCode, url=$url}"
}
