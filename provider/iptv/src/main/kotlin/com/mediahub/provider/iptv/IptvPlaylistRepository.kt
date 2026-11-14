package com.mediahub.provider.iptv

import com.mediahub.provider.api.ProviderException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * IPTV 播放列表 / EPG 的拉取、解析与内存缓存。
 *
 * 约束：
 * - 两类内容都是只读外部资源，**不落库**；stream URL / EPG URL 只驻留内存。
 * - 单次响应硬上限 [maxBytes]，防止异常源拖垮进程。
 * - 取消必须真实取消 OkHttp Call（协程取消红线）。
 * - 缓存 TTL 内复用解析结果，避免列表页/搜索/EPG 各拉一遍同一份播放列表。
 */
internal class IptvPlaylistRepository(
    private val serverId: String,
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private data class Cached<T>(val value: T, val loadedAtMs: Long, val sourceUrl: String)

    private val lock = Any()
    private var playlistCache: Cached<M3uPlaylist>? = null
    private var epgCache: Cached<List<com.mediahub.model.EpgProgram>>? = null

    suspend fun playlist(url: String, forceReload: Boolean = false): M3uPlaylist {
        val normalized = requireUrl(url)
        val now = clock()
        synchronized(lock) {
            val cached = playlistCache
            if (!forceReload && cached != null && cached.sourceUrl == normalized &&
                now - cached.loadedAtMs <= cacheTtlMs
            ) {
                return cached.value
            }
        }
        val text = fetchText(normalized)
        val parsed = try {
            M3uParser.parse(text)
        } catch (e: IllegalArgumentException) {
            throw ProviderException.Parse(serverId, e)
        }
        synchronized(lock) {
            playlistCache = Cached(parsed, clock(), normalized)
        }
        return parsed
    }

    suspend fun epg(url: String, forceReload: Boolean = false): List<com.mediahub.model.EpgProgram> {
        val normalized = requireUrl(url)
        val now = clock()
        synchronized(lock) {
            val cached = epgCache
            if (!forceReload && cached != null && cached.sourceUrl == normalized &&
                now - cached.loadedAtMs <= cacheTtlMs
            ) {
                return cached.value
            }
        }
        val text = fetchText(normalized)
        val parsed = try {
            XmltvParser.parse(text)
        } catch (e: IllegalArgumentException) {
            throw ProviderException.Parse(serverId, e)
        }
        synchronized(lock) {
            epgCache = Cached(parsed, clock(), normalized)
        }
        return parsed
    }

    private fun requireUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            throw ProviderException.Connection(serverId, "IPTV 地址为空")
        }
        if (trimmed.toHttpUrlOrNull() == null) {
            throw ProviderException.Parse(
                serverId,
                IllegalArgumentException("仅支持 http/https 的 IPTV 地址"),
            )
        }
        return trimmed
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            throw ProviderException.Parse(serverId, e)
        }
        val response = try {
            client.newCall(request).awaitResponse()
        } catch (e: IOException) {
            throw ProviderException.Network(serverId, e)
        }
        response.use { res ->
            if (!res.isSuccessful) {
                throw ProviderException.Http(serverId, res.code, sanitize(url))
            }
            val body = res.body ?: throw ProviderException.Parse(serverId)
            val declared = body.contentLength()
            if (declared > maxBytes) {
                throw ProviderException.Parse(
                    serverId,
                    IllegalArgumentException("IPTV 响应超过大小上限"),
                )
            }
            val bytes = readLimited(body)
            val content = if (isGzip(bytes)) {
                readLimited(GZIPInputStream(ByteArrayInputStream(bytes)))
            } else {
                bytes
            }
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            content.toString(charset).removePrefix("\uFEFF")
        }
    }

    private fun readLimited(body: okhttp3.ResponseBody): ByteArray {
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > maxBytes) {
                throw ProviderException.Parse(
                    serverId,
                    IllegalArgumentException("IPTV 响应超过大小上限"),
                )
            }
        }
        return buffer.readByteArray()
    }

    private fun readLimited(stream: GZIPInputStream): ByteArray {
        val buffer = Buffer()
        val chunk = ByteArray(READ_CHUNK_BYTES.toInt())
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw ProviderException.Parse(
                    serverId,
                    IllegalArgumentException("IPTV 响应解压后超过大小上限"),
                )
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.readByteArray()
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** 异常/诊断里只保留 scheme+host+path，去掉 userinfo / query / fragment。 */
    private fun sanitize(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?').substringBefore('#')
        return parsed.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response)
            }
        })
    }

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        const val READ_CHUNK_BYTES = 8192L
    }
}
