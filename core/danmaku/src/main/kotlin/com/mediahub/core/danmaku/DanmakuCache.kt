package com.mediahub.core.danmaku

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 弹幕缓存抽象：按稳定键读写已解析载荷，失败一律降级为未命中。 */
interface DanmakuCache {
    suspend fun read(key: String): String?
    suspend fun write(key: String, payload: String)
    suspend fun remove(key: String)
}

/** 进程内缓存（测试与无磁盘场景）。 */
class InMemoryDanmakuCache : DanmakuCache {
    private val entries = ConcurrentHashMap<String, String>()

    override suspend fun read(key: String): String? = entries[key]

    override suspend fun write(key: String, payload: String) {
        entries[key] = payload
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}

/**
 * 磁盘缓存：文件名为键的 SHA-256，避免路径注入与非法字符。
 * 过期条目视为未命中；任何 IO 异常都不向上抛（条目 59）。
 */
class FileDanmakuCache(
    private val directory: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DanmakuCache {

    override suspend fun read(key: String): String? = withContext(dispatcher) {
        try {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            if (ttlMs > 0 && now() - file.lastModified() > ttlMs) return@withContext null
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(key: String, payload: String) {
        withContext(dispatcher) {
            try {
                if (!directory.exists() && !directory.mkdirs()) return@withContext
                val target = fileFor(key)
                val temp = File(directory, "${target.name}.tmp")
                temp.writeText(payload)
                if (!temp.renameTo(target)) {
                    target.writeText(payload)
                    temp.delete()
                }
            } catch (_: Exception) {
                // 缓存失败不影响播放。
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(dispatcher) {
            runCatching { fileFor(key).delete() }
        }
    }

    private fun fileFor(key: String): File = File(directory, sha256(key) + ".txt")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
