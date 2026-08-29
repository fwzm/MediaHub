package com.mediahub.core.network

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.Redactor
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 普通 API 客户端（JSON）。
 *
 * 所有提供方（Emby/Jellyfin/WebDAV/云盘）共用该客户端；
 * 鉴权头由调用方以 [headers] 传入（来自各自 Provider 的会话管理），
 * 此处不做任何数据源特有逻辑。
 */
class ApiClient(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
    private val logger: Logger,
) {

    suspend inline fun <reified T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): T = execute("GET", url, headers, null, serializer<T>(), null)

    suspend inline fun <reified T> post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): T = execute("POST", url, headers, body, serializer<T>(), null)

    suspend inline fun <reified T> postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String = "{}",
    ): T = execute("POST", url, headers, jsonBody, serializer<T>(), "application/json")

    /** 发送请求但不解析响应体（如 Emby /Sessions/Logout 返回空 body）。 */
    suspend fun postNoContent(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Unit = executeNoContent("POST", url, headers, body, null)

    /** 通用执行：非 2xx 抛出 [ApiException]，返回体按 [deserializer] 解码。 */
    @PublishedApi
    internal suspend fun <T> execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        deserializer: DeserializationStrategy<T>,
        contentType: String?,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).method(method, buildBody(method, body, contentType))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val requestId = request.header(HttpClientFactory.HEADER_REQUEST_ID) ?: "?"

        val response = executeCancellable(request, readBody = true)
        if (!response.isSuccessful) {
            logger.w(
                LogTag.NETWORK,
                "API ${response.code} $method ${Redactor.redact(url)} " +
                    "body=${Redactor.redact(response.body).take(500)}"
            )
            throw ApiException(
                statusCode = response.code,
                url = url,
                method = method,
                requestId = requestId,
            )
        }
        try {
            json.decodeFromString(deserializer, response.body)
        } catch (e: Exception) {
            logger.e(LogTag.NETWORK, "JSON 解码失败 requestId=$requestId url=${Redactor.redact(url)}", e)
            throw e
        }
    }

    /** 与 [execute] 相同，但成功时不解码响应体。 */
    @PublishedApi
    internal suspend fun executeNoContent(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).method(method, buildBody(method, body, contentType))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val requestId = request.header(HttpClientFactory.HEADER_REQUEST_ID) ?: "?"

        val response = executeCancellable(request, readBody = false)
        if (!response.isSuccessful) {
            logger.w(
                LogTag.NETWORK,
                "API ${response.code} $method ${Redactor.redact(url)}"
            )
            throw ApiException(
                statusCode = response.code,
                url = url,
                method = method,
                requestId = requestId,
            )
        }
    }

    /**
     * OkHttp 异步调用的协程边界：取消协程时必须同时取消底层 Call。
     *
     * 不能在 Dispatchers.IO 中直接调用 Call.execute()：那只会取消协程的后续工作，
     * 不会中断正在阻塞的 socket/read，也会让上层 withTimeout/flatMapLatest 失去真实 deadline。
     */
    private suspend fun executeCancellable(request: Request, readBody: Boolean): BufferedResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val buffered = BufferedResponse(
                                    code = it.code,
                                    isSuccessful = it.isSuccessful,
                                    body = if (readBody) it.body?.string().orEmpty() else "",
                                    contentType = it.header("Content-Type"),
                                )
                                continuation.resumeWith(Result.success(buffered))
                            }
                        } catch (e: Exception) {
                            continuation.resumeWith(Result.failure(e))
                        }
                    }
                }
            )
        }

    private data class BufferedResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String,
        val contentType: String?,
    )

    private fun buildBody(method: String, body: String?, contentType: String?): RequestBody? {
        if (body == null) {
            // OkHttp 要求 POST 必须带 body（可为空）；GET/HEAD 用 null。
            return if (method == "POST") ByteArray(0).toRequestBody(null) else null
        }
        val mediaType = (contentType ?: "text/plain").toMediaType()
        return body.toRequestBody(mediaType)
    }

    /**
     * 服务器连通性探测（添加媒体库时的"测试连接"）。
     * 仅做基础 HTTP 探测，不包含任何数据源鉴权逻辑。
     */
    suspend fun probe(baseUrl: String, timeoutMs: Long = 10_000): ServerProbeResult {
        val normalized = baseUrl.trimEnd('/')
        val url = try {
            normalized.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return ServerProbeResult.Failure("URL 格式无效", e.message)
        }
        val request = Request.Builder().url(url).method("GET", null).build()
        val start = System.nanoTime()
        return try {
            val response = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                executeCancellable(request, readBody = false)
            } ?: return ServerProbeResult.Failure(
                userMessage = "连接服务器超时",
                detail = PlaybackError.Code.NETWORK_TIMEOUT.name,
            )
            ServerProbeResult.Success(
                httpCode = response.code,
                latencyMs = (System.nanoTime() - start) / 1_000_000,
                contentType = response.contentType,
            )
        } catch (e: IOException) {
            ServerProbeResult.Failure(
                userMessage = "无法连接服务器",
                detail = PlaybackErrorMapper.fromIoException(e).code.name,
            )
        }
    }
}

/** 连通性探测结果。 */
sealed interface ServerProbeResult {
    data class Success(
        val httpCode: Int,
        val latencyMs: Long,
        val contentType: String? = null,
    ) : ServerProbeResult

    data class Failure(
        val userMessage: String,
        val detail: String? = null,
    ) : ServerProbeResult
}
