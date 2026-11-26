package com.mediahub.core.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 401/403：签名 URL 失效或登录过期，交由 [DownloadSourceResolver] 重新解析。 */
internal class DownloadUnauthorizedException(val code: Int) : IOException("HTTP $code")

/** 其它非成功状态码（不含 401/403/416 的特殊处理）。 */
internal class DownloadHttpException(val code: Int) : IOException("HTTP $code")

/**
 * 基于 OkHttp 的下载传输（ADR-012：媒体流客户端，允许长读）。
 *
 * 设计红线（条目 68 / VPN 环境）：
 * - **不做任何“直连可达性 / 网络类型 / 非 VPN”预检**：是否可达完全交给 HTTP 栈
 *   （系统 VPN 在传输层接管路由；用户配置的 HTTP 代理也由注入的 [OkHttpClient] 生效）。
 * - 不发送 HEAD 预检请求：一次 GET 直达，避免代理/VPN 下额外的探测失败点。
 */
internal class HttpDownloadTransport(private val client: OkHttpClient) {

    /**
     * 下载到 [partial]（追加/覆盖由 Range 响应决定）。
     *
     * @return 落盘后的总字节数（= partial.length()）。
     */
    suspend fun fetch(
        source: ResolvedDownloadSource,
        request: DownloadRequest,
        partial: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var existing = if (partial.isFile) partial.length() else 0L
        // 已有片段超过期望大小说明是脏数据（例如换了远端文件），整体重下。
        if (existing > 0 && request.expectedSizeBytes != null && existing > request.expectedSizeBytes) {
            partial.delete()
            existing = 0L
        }

        val builder = Request.Builder().url(source.url)
        (request.headers + source.headers).forEach { (name, value) -> builder.header(name, value) }
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        val call = client.newCall(builder.build())
        val response = call.await()
        response.use { resp ->
            val body = resp.body
            when {
                resp.code == 401 || resp.code == 403 -> throw DownloadUnauthorizedException(resp.code)

                resp.code == 416 -> {
                    val expected = request.expectedSizeBytes
                    if (expected != null && partial.isFile && partial.length() == expected) {
                        return@withContext partial.length()
                    }
                    partial.delete()
                    throw DownloadHttpException(416)
                }

                resp.code == 200 -> {
                    if (partial.exists()) partial.delete()
                    downloadBody(resp, partial, startOffset = 0, total = body?.contentLength()?.takeIf { it >= 0 }, request, onProgress)
                }

                resp.code == 206 -> {
                    val contentRange = resp.header("Content-Range")
                    val start = parseRangeStart(contentRange) ?: existing
                    if (start != existing) {
                        // 服务器返回了与本地片段不匹配的区间：丢弃片段，语义上等价于完整重下。
                        partial.delete()
                        throw DownloadHttpException(206)
                    }
                    val total = parseRangeTotal(contentRange) ?: body?.contentLength()?.takeIf { it >= 0 }?.plus(start)
                    downloadBody(resp, partial, startOffset = start, total = total, request, onProgress)
                }

                resp.code in 200..299 -> throw DownloadHttpException(resp.code)
                else -> throw DownloadHttpException(resp.code)
            }
        }
    }

    private suspend fun downloadBody(
        response: Response,
        partial: File,
        startOffset: Long,
        total: Long?,
        request: DownloadRequest,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        val body = response.body ?: throw DownloadHttpException(response.code)
        partial.parentFile?.mkdirs()
        FileOutputStream(partial, startOffset > 0).use { output ->
            var written = startOffset
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(written, total ?: request.expectedSizeBytes)
                }
            }
            output.flush()
            val expected = request.expectedSizeBytes ?: total
            if (expected != null && written < expected) {
                // 连接提前结束：保留 .part，下一次 Range 续传（不标记完成）。
                throw IOException("下载不完整：$written/$expected")
            }
            return written
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                } else {
                    cont.resume(response)
                }
            }
        })
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** `Content-Range: bytes 4-9/10` → 4。 */
        fun parseRangeStart(header: String?): Long? {
            val value = header ?: return null
            val part = value.substringAfter("bytes ", "").substringBefore('-').trim()
            return part.toLongOrNull()
        }

        /** `Content-Range: bytes 4-9/10` → 10；`*` → null。 */
        fun parseRangeTotal(header: String?): Long? {
            val value = header ?: return null
            val total = value.substringAfter('/', "").trim()
            return total.toLongOrNull()
        }
    }
}
