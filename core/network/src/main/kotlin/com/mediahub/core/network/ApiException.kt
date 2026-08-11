package com.mediahub.core.network

/** 服务端 API 调用失败（含可诊断信息，日志必须脱敏）。 */
class ApiException(
    val statusCode: Int,
    val url: String,
    val method: String,
    val requestId: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: "HTTP $statusCode $method $url", cause) {

    fun toLogString(): String =
        "ApiException{requestId=$requestId, method=$method, status=$statusCode, url=$url}"
}
