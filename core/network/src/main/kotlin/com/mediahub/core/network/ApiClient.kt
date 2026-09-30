package com.mediahub.core.network

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.Redactor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /**
     * 发送请求但不解析响应体（如 Emby /Sessions/Logout 返回空 body）。
     * [contentType]：body 非 null 时的媒体类型（如 Jellyfin /Sessions/Playing 系列的
     * "application/json"，[FromBody] 需要 JSON content type；Phase 1G-C，ADR-039）。
     */
    suspend fun postNoContent(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
    ): Unit = executeNoContent("POST", url, headers, body, contentType)

    /** 通用执行：非 2xx 抛出 [ApiException]，返回体按 [deserializer] 解码。 */
    @PublishedApi
    internal suspend fun <T> execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        deserializer: DeserializationStrategy<T>,
        contentType: String?,
    ): T {
        val request = buildRequest(method, url, headers, body, contentType)
        val requestId = request.header(HttpClientFactory.HEADER_REQUEST_ID) ?: "?"
        return client.newCall(request).awaitCancellable { response ->
            response.use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    logger.w(
                        LogTag.NETWORK,
                        "API ${response.code} $method ${Redactor.redact(url)} body=${Redactor.redact(responseBody.take(500))}"
                    )
                    throw ApiException(
                        statusCode = response.code,
                        url = url,
                        method = method,
                        requestId = requestId,
                    )
                }
                try {
                    json.decodeFromString(deserializer, responseBody)
                } catch (e: Exception) {
                    logger.e(LogTag.NETWORK, "JSON 解码失败 requestId=$requestId url=${Redactor.redact(url)}", e)
                    throw e
                }
            }
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
    ): Unit {
        val request = buildRequest(method, url, headers, body, contentType)
        val requestId = request.header(HttpClientFactory.HEADER_REQUEST_ID) ?: "?"
        client.newCall(request).awaitCancellable { response ->
            response.use { response ->
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
        }
    }

    private fun buildRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
    ): Request {
        val builder = Request.Builder().url(url).method(method, buildBody(method, body, contentType))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /**
     * 可取消 HTTP 桥接（Phase 1H SLOW-FINAL 修复）：以 [Call.enqueue] 代替阻塞的
     * [Call.execute]，并把协程取消桥接为 [Call.cancel]。
     *
     * 缺陷实证（provider/emby SlowFinalExitRegressionTest，1H SLOW-FINAL）：阻塞
     * execute 无法被 withTimeout 打断——withContext(Dispatchers.IO) 要等块跑完才检查
     * 取消，进度 final flush 的 2000ms 退出预算被慢网络击穿（退出路径阻塞至
     * readTimeout）。桥接后取消立即终止在途 Call，预算恢复生效。响应在 OkHttp 回调
     * 线程内消费并关闭（不占用调用方线程）；取消竞态与迟到响应都会关闭响应，无所有权
     * 空隙——与 [EndpointTestService] 的 awaitCancellable 同款约定。
     */
    private suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!cont.isActive) return
                            block(it)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    // OkHttp 会把拦截器链抛出的 CancellationException 包成
                    // IOException("canceled due to …") 并 addSuppressed；解包以维持
                    // "取消原样传播、绝不折叠成业务异常"契约（ADR-039 §10）。
                    val cancellation = e.suppressed.filterIsInstance<CancellationException>().firstOrNull()
                    if (cancellation != null) cont.resumeWithException(cancellation)
                    else cont.resumeWithException(e)
                }
            })
        }

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
     *
     * 超时语义（A4，取代 A3-5 登记的缺陷语义）：[timeoutMs] 是**真实 deadline**——
     * 经 [withTimeoutOrNull] 包裹 enqueue 桥（[awaitCancellable]）实现：
     * - deadline 命中：返回 [ServerProbeResult.Failure]（userMessage="连接超时"，
     *   detail 对齐 [PlaybackError.Code.NETWORK_TIMEOUT]），并经
     *   `invokeOnCancellation → Call.cancel` **真实打断**在途请求，不等
     *   OkHttp 的 readTimeout/callTimeout。
     * - 外层协程取消：同样经真实 `Call.cancel` 立即打断（取消透传，不折叠为
     *   Failure）；IO 失败（连接拒绝/DNS 等）仍按旧契约映射
     *   "无法连接服务器" + [PlaybackErrorMapper] 对应码。
     * - 全程 enqueue 异步回调，不占用任何调用方线程阻塞等待。
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
        val outcome = try {
            withTimeoutOrNull(timeoutMs) {
                client.newCall(request).awaitCancellable { response ->
                    response.use {
                        ServerProbeResult.Success(
                            httpCode = it.code,
                            latencyMs = (System.nanoTime() - start) / 1_000_000,
                            contentType = it.header("Content-Type"),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            return ServerProbeResult.Failure(
                userMessage = "无法连接服务器",
                detail = PlaybackErrorMapper.fromIoException(e).code.name,
            )
        }
        // deadline 命中（底层 Call 已被 withTimeoutOrNull 的取消真实打断）
        return outcome ?: ServerProbeResult.Failure(
            userMessage = "连接超时",
            detail = PlaybackError.Code.NETWORK_TIMEOUT.name,
        )
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
