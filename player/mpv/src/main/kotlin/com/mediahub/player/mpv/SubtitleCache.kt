package com.mediahub.player.mpv

import android.net.Uri
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.OriginScopedCredentialInterceptor
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
) {
    /** SAF content: 拷贝边界（可测注入）。 */
    fun interface ContentCopy {
        fun copy(uri: Uri, target: File): Boolean
    }

    /**
     * 会话生命周期（A4 终审）：缓存资源归属**当前播放会话**——
     * - [beginSession]：新会话开始（引擎 play 成功路径调用），清空上一会话残留
     *   并打开本会话的落地资格（防旧会话迟到下载把文件重新写回共享目录）；
     * - [endSession]：会话终止（引擎 stop/release 调用），清空本会话缓存并关闭
     *   落地资格——迟到下载完成时因资格关闭而丢弃（fail-closed），不会被后续
     *   sub-add 复用（sub-add 本身已被引擎 isCurrent 闸门丢弃）。
     * 未 begin（引擎从未 play）时本地落地一律 fail-closed 返回 null。
     */
    @Volatile
    private var sessionActive = true

    fun beginSession() {
        sessionActive = true
        File(cacheDir, DIR).apply {
            listFiles()?.forEach { it.delete() }
            mkdirs()
        }
    }

    fun endSession() {
        sessionActive = false
        File(cacheDir, DIR).apply {
            listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 返回 mpv 可直挂的本地路径；无法落地返回 null。
     *
     * @param scopeKey 媒体版本指纹（缓存隔离键，调用方传入，**非空**）：
     *   同名字幕在不同 scope 下互不复用缓存文件。
     */
    open suspend fun localPathFor(
        uri: String,
        mediaUrl: String,
        scopeKey: String,
        sessionHeaders: Map<String, String>,
    ): String? {
        require(scopeKey.isNotBlank()) { "scopeKey must not be blank (media version fingerprint)" }
        if (!sessionActive) return null // 会话终止后迟到的字幕请求 fail-closed
        return withContext(Dispatchers.IO) {
            runCatching { resolve(uri, mediaUrl, scopeKey, sessionHeaders) }
                .onFailure { logger.w(LogTag.PLAYER, "mpv 外挂字幕落地失败 uri=$uri", it) }
                .getOrNull()
        }
    }

    private suspend fun resolve(
        uri: String,
        mediaUrl: String,
        scopeKey: String,
        sessionHeaders: Map<String, String>,
    ): String? {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        val result = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> {
                val target = File(dir, scopedFileName(uri, scopeKey))
                if (!target.exists() || target.length() == 0L) {
                    download(uri, mediaUrl, sessionHeaders, target)
                }
                target.takeIf { it.length() > 0L }?.absolutePath
            }

            uri.startsWith("content://") -> {
                val target = File(dir, scopedFileName(uri, scopeKey))
                if (!target.exists() || target.length() == 0L) {
                    // A4-C3：先写 .part 再 rename（对齐 http 路径）——SAF 流中途失败时
                    // 半成品不再以正式文件名残留，下次请求不会被 exists&&len>0 误复用。
                    val part = File(dir, target.name + ".part")
                    val copied = runCatching {
                        part.delete()
                        contentResolver.copy(Uri.parse(uri), part)
                    }.getOrDefault(false)
                    if (!copied || !part.renameTo(target) || target.length() == 0L) {
                        part.delete()
                        target.delete()
                        return null
                    }
                }
                target.takeIf { it.length() > 0L }?.absolutePath
            }

            uri.startsWith("file://") -> Uri.parse(uri).path

            // 本地绝对路径（Local Provider；isAbsolute 兼容 POSIX/Windows 语义）
            else -> uri.takeIf { it.startsWith("/") || java.io.File(it).isAbsolute }
        }
        // 成功落地（非 null 且非本地直挂场景变化）后执行失效策略
        if (result != null && (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("content://"))) {
            enforceCacheBounds(dir)
        }
        return result
    }

    /**
     * 下载（A4-C5 协程取消可中断）：阻塞 execute 在专用桥接守护线程执行
     * （对齐 provider/webdav WebDavCallBridge 模式的本地实现）；协程取消 →
     * `invokeOnCancellation { call.cancel() }` → 真实打断等头/读体，socket
     * 不再等超时。响应所有权在桥接线程内 use{} 关闭；.part 在任何退出路径清理。
     */
    private suspend fun download(
        uri: String,
        mediaUrl: String,
        sessionHeaders: Map<String, String>,
        target: File,
    ) = suspendCancellableCoroutine { cont ->
        // 凭据作用域：仅同 origin 媒体允许携带会话头（WebDAV Basic 场景）。
        val attachHeaders = if (sameOrigin(mediaUrl, uri)) sessionHeaders else emptyMap()
        val builder = Request.Builder().url(uri)
        attachHeaders.forEach { (k, v) -> builder.header(k, v) }
        val tmp = File(target.parentFile, target.name + ".part")
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        BRIDGE_EXECUTOR.execute {
            // 排队后被取消（CallerRuns 防御路径）：不发起网络，直接让出
            if (!cont.isActive) return@execute
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("subtitle download HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("subtitle download empty body")
                    tmp.outputStream().use { out ->
                        // A4-C4：下载复制循环上限 4 MiB——字幕不可能超过该量级，
                        // 超限视为服务端异常/攻击面，立即失败。
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                            if (total > MAX_SUBTITLE_BYTES) {
                                throw IOException("subtitle exceeds ${MAX_SUBTITLE_BYTES / (1024 * 1024)} MiB cap ($total bytes)")
                            }
                        }
                    }
                    if (!sessionActive) {
                        // A4：落地前再查会话资格——stop 与下载交错时，迟到的下载不重新落地
                        tmp.delete()
                    } else if (!tmp.renameTo(target)) {
                        throw IOException("subtitle cache rename failed")
                    }
                }
                if (cont.isActive) cont.resume(Unit)
            } catch (t: Throwable) {
                tmp.delete()
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    /**
     * 失效策略（A4-C6）：每次成功落地后执行——文件数上限 [MAX_CACHE_FILES]、
     * 总字节上限 [MAX_CACHE_TOTAL_BYTES]，超限按 lastModified 淘汰最旧；
     * 孤儿 .part（失败/取消残留）一并清理。
     */
    private fun enforceCacheBounds(dir: File) {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() } ?: return
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }?.forEach { it.delete() }
        var total = files.sumOf { it.length() }
        var index = 0
        while ((files.size - index > MAX_CACHE_FILES || total > MAX_CACHE_TOTAL_BYTES) && index < files.size) {
            val oldest = files[index]
            val size = oldest.length()
            if (oldest.delete()) {
                total -= size
            }
            index++
        }
    }

    /** 会话清理（A4-C6）：引擎 stop 时调用，清空全部字幕缓存。 */
    fun clearSession() {
        val dir = File(cacheDir, DIR)
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 缓存键 = sha256(scopeKey) 前 16 hex + "_" + 原始文件名（A4-C2）。
     * scopeKey 是媒体版本指纹：同名字幕在不同 scope（不同目录/服务器/媒体版本）
     * 下互不复用——原实现仅按文件名碰撞复用，A 服务器同名字幕内容会错误挂到
     * B 服务器播放；同 scope 重复请求仍命中同一文件（缓存语义保留）。
     */
    private fun scopedFileName(uri: String, scopeKey: String): String {
        val scopeHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(scopeKey.toByteArray(Charsets.UTF_8))
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
        // 文件名碰撞即复用同名字幕（同一视频同名字幕内容一致的场景占绝对多数）；
        // 无法保证时由 extension 白名单兜底，绝不写目录逃逸路径。
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
