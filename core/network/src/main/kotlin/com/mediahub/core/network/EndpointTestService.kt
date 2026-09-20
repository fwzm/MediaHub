package com.mediahub.core.network

import android.os.SystemClock
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 线路测试结果（U4-D）。 */
data class EndpointTestResult(
    val apiLatencyMs: Long,
    val mediaFirstByteMs: Long?,
    val mediaThroughputMbps: Double?,
    val httpCode: Int,
    val protocol: String?,
    val supportsRange: Boolean,
    val error: String? = null,
)

/**
 * 两层线路测试（U4-D）：
 * 1. API Test：GET {baseUrl + probePath}，测 DNS+TCP+TLS+HTTP 总延迟。
 * 2. Media Test：Range 1MB 模拟真实播放首包+吞吐。
 * 不下载完整视频；不记录 token/URL。
 *
 * Phase 1G-A（ADR-039）：[probePath] 由调用方从 ProviderDescriptor.probePath 传入——
 * 本类**不含任何 Emby/Jellyfin 协议路径知识**（/emby 前缀属于 Emby provider 自述）。
 *
 * Phase 1I 2C（取消契约）：两层探测通过 [ioDispatcher] 发起异步请求，
 * 响应在 OkHttp 回调线程内消费，不占用调用方（编辑页主调度器）线程；请求绑定当前协程，
 * 取消会实际终止对应 [Call] 并以 [CancellationException] 向上传播；
 * 响应所有权由桥接统一持有，成功、失败、取消竞态与迟到响应都会关闭。
 * 媒体响应按 [maxMediaBytes] 上限消费，落实注释中既有的 Range 1MB 承诺。
 *
 * open：Phase 1I 线路质量测试的陈旧结果隔离需要可控延迟的测试替身
 * （EmbyApiClient/EndpointTestService 同款 open-for-test 约定）。
 */
open class EndpointTestService(
    private val clientFactory: HttpClientFactory,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxMediaBytes: Long = MAX_MEDIA_BYTES,
) {

    /**
     * 测试接缝：仅供测试替换底层客户端以观察 Call 级取消与资源事件。
     * 生产路径固定走 [HttpClientFactory]，不改变其超时与重试策略。
     */
    protected open fun createApiClient(): OkHttpClient = clientFactory.apiClient()

    protected open fun createMediaClient(): OkHttpClient = clientFactory.mediaClient()

    /**
     * 测试接缝：允许测试注入受控 [Call]，用于观察取消传播与响应释放竞态。
     * 生产路径等价于 `client.newCall(request)`。
     */
    protected open fun newCall(client: OkHttpClient, request: Request): Call =
        client.newCall(request)

    open suspend fun test(baseUrl: String, probePath: String): EndpointTestResult =
        withContext(ioDispatcher) {
            // 开始前已取消：不发出任何请求
            coroutineContext.ensureActive()

            val probeUrl = baseUrl.trimEnd('/') + probePath
            var apiLatency = -1L
            var mediaFirstByte: Long? = null
            var throughput: Double? = null
            var code = 0
            var protocol: String? = null
            var rangeOk = false
            var errorMsg: String? = null

            // ---- Layer 1: API latency ----
            val apiCall = newCall(createApiClient(), Request.Builder().url(probeUrl).build())
            try {
                val start = clock()
                apiCall.awaitCancellable { resp ->
                    apiLatency = clock() - start
                    code = resp.code
                    protocol = resp.protocol?.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 取消引起的 IOException 不得被当成普通失败吞掉
                coroutineContext.ensureActive()
                errorMsg = "API test failed: ${e.message}"
            } catch (e: Exception) {
                errorMsg = "API test failed: ${e.message}"
            }

            // ---- Layer 2: Media Range（受控交接窗口：API 完成、Media 尚未开始）----
            if (errorMsg == null) {
                coroutineContext.ensureActive()
                val mediaCall = newCall(
                    createMediaClient(),
                    Request.Builder()
                        .url(probeUrl) // placeholder, real impl uses a known item ID
                        .header("Range", "bytes=0-${MAX_MEDIA_BYTES - 1}")
                        .build()
                )
                try {
                    val start = clock()
                    mediaCall.awaitCancellable { resp ->
                        mediaFirstByte = clock() - start
                        rangeOk = resp.code == 206 || resp.header("Accept-Ranges") == "bytes"
                        val bytes = resp.body?.readAtMost(maxMediaBytes) ?: 0L
                        val elapsedSec = (clock() - start) / 1000.0
                        if (elapsedSec > 0 && bytes > 0) {
                            throughput = (bytes / (1024.0 * 1024.0)) / elapsedSec
                        }
                        code = resp.code
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    // media test failure doesn't invalidate API result
                } catch (e: Exception) {
                    // media test failure doesn't invalidate API result
                }
            }

            coroutineContext.ensureActive()

            EndpointTestResult(
                apiLatencyMs = apiLatency,
                mediaFirstByteMs = mediaFirstByte,
                mediaThroughputMbps = throughput,
                httpCode = code,
                protocol = protocol,
                supportsRange = rangeOk,
                error = errorMsg,
            )
        }

    /**
     * 可取消桥接：以 [Call.enqueue] 代替阻塞的 [Call.execute]，
     * 回调持有响应直至 [block] 消费并关闭；跨 continuation 只交付不持有响应的结果。
     * 取消钩子在消费期间仍有效，可中止停滞读取，也不会留下恢复前取消的所有权空隙。
     */
    private suspend fun <T> Call.awaitCancellable(block: (Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

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
            })
        }

    /** 按上限消费响应体；返回实际消费字节数。不读取超出 [limit] 的字节。 */
    private fun ResponseBody.readAtMost(limit: Long): Long {
        if (limit <= 0L) return 0L
        var total = 0L
        val buffer = Buffer()
        source().use { source ->
            while (total < limit) {
                val want = minOf(READ_CHUNK_BYTES, limit - total)
                val read = source.read(buffer, want)
                if (read == -1L) break
                total += read
                buffer.clear()
            }
        }
        return total
    }

    companion object {
        /** 媒体采样上限：与 Range 请求头一致（1 MiB）。 */
        const val MAX_MEDIA_BYTES: Long = 1024L * 1024L
        private const val READ_CHUNK_BYTES = 8L * 1024L
    }
}
