package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.provider.api.ProviderException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer

/**
 * WebDAV 只读协议客户端：`PROPFIND`（RFC 4918 §9.1）。
 *
 * 设计边界：
 * - **不自动跟随重定向**（`followRedirects=false`）。客户端跟随重定向时是否剥离
 *   `Authorization` 取决于具体实现与版本（ADR-030 已记录 media3 1.5.1 无剥离逻辑的
 *   真实泄漏），因此这里显式关闭跟随，把 3xx 变成结构化错误 —— 凭据绝不发往第二跳。
 * - **响应体有界读取**：目录可能极大或被恶意构造；超过 [MAX_RESPONSE_BYTES] 直接
 *   `Parse` 失败，不把整个 body 读进内存。
 * - **不做服务端分页**：PROPFIND 无分页语义。目录结果在本层一次性取回，
 *   分页由上层对结果做本地切片；本类不声明任何服务端分页能力。
 * - 取消必须穿透：`CancellationException` 原样上抛，不折叠为协议错误（ADR-039）。
 */
internal class WebDavApi(
    private val serverId: String,
    mediaHttpClient: MediaHttpClient,
    private val logger: Logger,
) {

    private val client: OkHttpClient = mediaHttpClient.okHttpClient().newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 对 [url] 发起 PROPFIND。
     *
     * @param depth 0 = 仅资源自身；1 = 资源自身 + 直接子级。
     * @param authorization 完整的 `Authorization` 头值（`Basic ...`）；null 表示匿名。
     */
    suspend fun propfind(url: String, depth: Int, authorization: String?): List<WebDavResource> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", depth.toString())
            if (authorization != null) builder.header("Authorization", authorization)
            val request = builder.build()

            coroutineContext.ensureActive()
            val response = try {
                client.newCall(request).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.w(LogTag.NETWORK, "WebDAV PROPFIND 失败 depth=$depth", e)
                throw ProviderException.Network(serverId, e)
            }

            response.use { resp ->
                coroutineContext.ensureActive()
                if (resp.code !in 200..299) throw mapStatus(resp.code, url)
                val body = resp.body ?: throw ProviderException.Parse(serverId)
                val xml = readBounded(body)
                try {
                    WebDavMultistatusParser.parse(xml)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(LogTag.NETWORK, "WebDAV multistatus 解析失败 url=$url", e)
                    throw ProviderException.Parse(serverId, e)
                }
            }
        }

    private fun readBounded(body: ResponseBody): String {
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) throw ProviderException.Parse(serverId)
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw ProviderException.Parse(serverId)
        }
        return buffer.readString(Charsets.UTF_8)
    }

    private fun mapStatus(code: Int, url: String): ProviderException = when (code) {
        401 -> ProviderException.AuthExpired(serverId)
        403 -> ProviderException.Http(serverId, code, url, METHOD)
        404, 410 -> ProviderException.NotFound(serverId, url)
        429 -> ProviderException.RateLimited(serverId)
        // 3xx：本层不跟随重定向（凭据不得随跳转外流），交给用户修正地址。
        else -> ProviderException.Http(serverId, code, url, METHOD)
    }

    private companion object {
        const val METHOD = "PROPFIND"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 60L
        const val CHUNK_BYTES = 8192L

        /** 单个目录响应的硬上限（8 MiB）。 */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getcontenttype/>
                <D:getlastmodified/>
                <D:getetag/>
                <D:displayname/>
              </D:prop>
            </D:propfind>
        """.trimIndent()
    }
}
