package com.mediahub.player.mpv

import android.net.Uri
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * mpv 外挂字幕落地缓存（P2 字幕中心切片一）。
 *
 * 能力边界（如实实现，不过度声明）：
 * - 本地路径（file:// 或绝对路径）：原样返回（mpv 可直挂）；
 * - http(s)：下载到 cacheDir/subtitles/ 后 `sub-add` 本地文件。
 *   鉴权头（如 WebDAV Basic）**仅当字幕 URI 与当前媒体同 origin 时**随请求发出
 *   （与 [OriginScopedCredentialInterceptor] 同一红线：凭据绝不发往第三方主机）；
 * - content:（SAF 导入）：经 ContentResolver 拷贝到 cache 后直挂。
 * 任何失败返回 null（调用方如实报"加载失败"，不伪造成功）。
 */
internal open class SubtitleCache(
    private val cacheDir: File,
    private val client: OkHttpClient,
    private val contentResolver: ContentCopy,
    private val logger: Logger,
    private val beforeCommit: (File) -> Unit = {},
) {
    /** SAF content: 拷贝边界（可测注入）。 */
    fun interface ContentCopy {
        fun copy(uri: Uri, target: File): Boolean
    }

    /** All ownership transitions and file publication use this monitor. Tokens never revive. */
    private val ownershipLock = Any()
    internal class Session internal constructor(val prefix: String) {
        internal val files = mutableSetOf<File>()
        internal val parts = mutableSetOf<File>()
        internal val calls = mutableSetOf<Call>()
    }
    private val instancePrefix = java.util.UUID.randomUUID().toString() + "_"
    private var sessionGeneration = 0L
    // Standalone cache consumers start with a session; the engine binds its first use to a Run.
    private var activeSession: Session? = newSession()
    private fun newSession() = Session(instancePrefix + (++sessionGeneration))
    fun sessionToken(): Session? = synchronized(ownershipLock) { activeSession }

    fun beginSession(): Session = synchronized(ownershipLock) {
        activeSession?.let { endSession(it) }
        newSession().also { activeSession = it }
    }

    fun endSession(token: Session? = sessionToken()) = synchronized(ownershipLock) {
        if (token == null) return@synchronized
        if (activeSession === token) activeSession = null
        token.calls.toList().forEach { it.cancel() }
        // Only files owned by this token: a late cleanup cannot delete a newer session's files.
        token.files.removeAll { it.delete() || !it.exists() }
        token.parts.forEach { it.delete() }
    }

    open suspend fun localPathFor(
        uri: String,
        mediaUrl: String,
        scopeKey: String,
        sessionHeaders: Map<String, String>,
        token: Session? = sessionToken(),
    ): String? {
        require(scopeKey.isNotBlank()) { "scopeKey must not be blank (media version fingerprint)" }
        if (token == null || synchronized(ownershipLock) { activeSession !== token }) return null
        return withContext(Dispatchers.IO) {
            try {
                resolve(uri, mediaUrl, scopeKey, sessionHeaders, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not log credential-bearing URI/query strings.
                logger.w(LogTag.PLAYER, "mpv external subtitle cache failed")
                null
            }
        }
    }

    private suspend fun resolve(
        uri: String,
        mediaUrl: String,
        scopeKey: String,
        sessionHeaders: Map<String, String>,
        token: Session,
    ): String? {
        val remote = uri.startsWith("http://") || uri.startsWith("https://")
        val content = uri.startsWith("content://")
        if (!remote && !content) return synchronized(ownershipLock) {
            if (activeSession !== token) null
            else if (uri.startsWith("file://")) Uri.parse(uri).path
            else uri.takeIf { it.startsWith("/") || File(it).isAbsolute }
        }
        val job = currentCoroutineContext()
        val dir = File(cacheDir, DIR)
        val target = File(dir, "${token.prefix}_${scopedFileName(uri, scopeKey)}")
        synchronized(ownershipLock) {
            if (activeSession !== token) return null
            if (target in token.files && target.length() > 0L) return target.absolutePath
            dir.mkdirs()
        }
        // Each request owns a distinct part, even when two requests import the same URI.
        val part = File.createTempFile(target.name + "_", ".part", dir)
        synchronized(ownershipLock) { token.parts += part }
        var published = false
        try {
            if (remote) download(uri, mediaUrl, sessionHeaders, part, token)
            else if (!contentResolver.copy(Uri.parse(uri), part)) return null
            beforeCommit(part) // deterministic test seam at the real, closed-file publication window
            job.ensureActive()
            return synchronized(ownershipLock) {
                job.ensureActive()
                if (activeSession !== token || part.length() == 0L || part.length() > MAX_SUBTITLE_BYTES) return@synchronized null
                if (target in token.files && target.length() > 0L) return@synchronized target.absolutePath
                // Do not evict subtitles that mpv can still be using. Bound admission instead.
                if (token.files.size >= MAX_CACHE_FILES || token.files.sumOf { it.length() } + part.length() > MAX_CACHE_TOTAL_BYTES) return@synchronized null
                if (!part.renameTo(target)) throw IOException("subtitle cache rename failed")
                token.files += target
                published = true
                enforceCacheBounds(dir)
                target.absolutePath
            }
        } finally {
            synchronized(ownershipLock) {
                token.parts -= part
                part.delete()
                if (published && !job.isActive) {
                    token.files -= target
                    target.delete()
                }
            }
        }
    }

    private suspend fun download(
        uri: String,
        mediaUrl: String,
        sessionHeaders: Map<String, String>,
        part: File,
        token: Session,
    ) = suspendCancellableCoroutine { cont ->
        val attachHeaders = if (sameOrigin(mediaUrl, uri)) sessionHeaders else emptyMap()
        val builder = Request.Builder().url(uri)
        attachHeaders.forEach { (k, v) -> builder.header(k, v) }
        val call = client.newCall(builder.build())
        synchronized(ownershipLock) {
            if (activeSession !== token) call.cancel() else token.calls += call
        }
        cont.invokeOnCancellation { call.cancel() }
        BRIDGE_EXECUTOR.execute {
            try {
                if (!cont.isActive) return@execute
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("subtitle download HTTP ${response.code}")
                    val body = response.body ?: throw IOException("subtitle download empty body")
                    part.outputStream().use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            if (!cont.isActive) throw CancellationException("subtitle download cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_SUBTITLE_BYTES) throw IOException("subtitle exceeds size cap")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                if (cont.isActive) cont.resume(Unit)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            } finally {
                synchronized(ownershipLock) { token.calls -= call }
                // On cancellation the coroutine may already have completed its finally before this
                // worker's output stream closes. Remove its private part once I/O really ends.
                if (cont.isCancelled) part.delete()
            }
        }
    }

    /** Called under ownershipLock; active parts and delivered files cannot be evicted. */
    private fun enforceCacheBounds(dir: File) {
        val protected = activeSession?.files.orEmpty()
        val parts = activeSession?.parts.orEmpty()
        // Another engine/cache instance can share cacheDir while old native teardown finishes.
        // Its UUID namespace is never this instance's eviction/cleanup responsibility.
        fun ownedOrLegacy(file: File) = file.name.startsWith(instancePrefix) || !MANAGED_FILE_PREFIX.containsMatchIn(file.name)
        dir.listFiles { f -> f.isFile && ownedOrLegacy(f) && f.name.endsWith(".part") && f !in parts }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && ownedOrLegacy(f) && !f.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() } ?: return
        var count = files.size
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (count <= MAX_CACHE_FILES && total <= MAX_CACHE_TOTAL_BYTES) break
            if (file in protected) continue
            val size = file.length()
            if (file.delete()) { count--; total -= size }
        }
    }

    fun clearSession(token: Session? = sessionToken()) = endSession(token)

    /**
     * 缓存键包含会话唯一前缀、sha256(scopeKey + 完整 URI) 和安全文件名。
     * scopeKey 是媒体版本指纹：同名字幕在不同 scope（不同目录/服务器/媒体版本）
     * 下互不复用——原实现仅按文件名碰撞复用，A 服务器同名字幕内容会错误挂到
     * B 服务器播放；同 scope 重复请求仍命中同一文件（缓存语义保留）。
     */
    private fun scopedFileName(uri: String, scopeKey: String): String {
        val scopeHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((scopeKey + "\u0000" + uri).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "${scopeHash}_${stableFileName(uri)}"
    }

    private fun stableFileName(uri: String): String {
        val raw = uri.substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val name = decoded.ifBlank { "subtitle" }
        // 完整 URI 哈希隔离同名资源；保留 basename 扩展名并移除路径分隔符。
        return name.substringAfterLast('/').substringAfterLast('\\').take(128)
    }

    private fun sameOrigin(a: String, b: String): Boolean {
        val originA = originOf(a) ?: return false
        val originB = originOf(b) ?: return false
        return originA.equals(originB, ignoreCase = true)
    }

    /**
     * origin（scheme://host:port），端口按 scheme 默认值归一（A4-C1）：
     * 隐式端口（[java.net.URI.getPort] 返回 -1）归一到默认（http→80、https→443），
     * 显式默认端口保持等值——语义对齐 provider/webdav WebDavModel.origin 的 effectivePort。
     * 未显式端口的 -1 直接拼接曾导致"隐式 ↔ 显式 :80/:443"误判异源、同源凭据被丢弃。
     */
    private fun originOf(url: String): String? = try {
        val parsed = java.net.URI(url)
        if (parsed.host.isNullOrBlank()) null
        else {
            val scheme = parsed.scheme?.lowercase()
            if (scheme.isNullOrBlank()) {
                null
            } else {
                val declared = parsed.port
                val effectivePort = when {
                    declared > 0 -> declared
                    scheme == "https" -> 443
                    else -> 80
                }
                "$scheme://${parsed.host.lowercase()}:$effectivePort"
            }
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val DIR = "subtitles"
        val MANAGED_FILE_PREFIX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_")

        /** 单个字幕缓存文件上限（4 MiB）：字幕文体量远小于此，超限按异常处理。 */
        const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024

        /** 缓存文件数上限（LRU 淘汰最旧）。 */
        const val MAX_CACHE_FILES = 64

        /** 缓存总字节上限（32 MiB，LRU 淘汰最旧）。 */
        const val MAX_CACHE_TOTAL_BYTES = 32L * 1024 * 1024

        /** 下载桥接线程池：守护线程、有界（字幕下载为低频小流量）。 */
        private val BRIDGE_EXECUTOR = java.util.concurrent.ThreadPoolExecutor(
            0,
            4,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            { task -> Thread(task, "mpv-subtitle-bridge").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy(),
        )
    }
}
