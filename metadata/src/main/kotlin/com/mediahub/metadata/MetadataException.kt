package com.mediahub.metadata

/**
 * 元数据数据源调用失败。
 *
 * [statusCode] 为 HTTP 状态码（网络层失败时为 null）。消息不含请求 URL / API Key，
 * 避免凭据经异常文本外泄（见 `TmdbMetadataProviderTest` 的泄漏断言）。
 */
class MetadataException(
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
