package com.mediahub.feature.player

import com.mediahub.model.SubtitleEncoding
import java.io.File
import java.security.MessageDigest

/**
 * 将非 UTF-8 字幕落盘为 UTF-8 缓存；纯 JVM 可测。
 *
 * 只处理需要转码的内容：已是 UTF-8 时返回 null，调用方继续用原 uri。
 * 文件按原始字节的 SHA-256 命名，同一字幕重复选择不会重复写盘。
 */
class SubtitleTextCache(private val cacheDir: File) {

    @Synchronized
    fun prepare(bytes: ByteArray, extension: String): File? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val normalized = SubtitleEncoding.normalizeToUtf8(bytes)
        if (normalized.contentEquals(bytes)) return null
        val directory = File(cacheDir, "subtitles")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val safeExtension = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "txt" }
        val target = File(directory, "$digest.$safeExtension")
        if (target.isFile && target.length() == normalized.size.toLong()) return target
        return try {
            val temporary = File.createTempFile("subtitle-", ".tmp", directory)
            temporary.writeBytes(normalized)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            prune(directory)
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun prune(directory: File) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    companion object {
        /** 4 MiB 上限：外挂字幕文本足够，避免误读大文件。 */
        const val MAX_BYTES: Int = 4 * 1024 * 1024
        private const val MAX_FILES = 32
    }
}
