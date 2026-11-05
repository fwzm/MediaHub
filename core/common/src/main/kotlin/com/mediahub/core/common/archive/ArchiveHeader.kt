package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.WireText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 归档的明文头部（ADR-S005）。逐字对齐 Windows `MediaHub.Sync.ArchiveHeader`。
 *
 * 为什么头部不加密：回答「这是哪个版本 / 哪个 kdf / 多少轮迭代」必须在不输入口令的前提下可行，
 * 否则用户看到的第一条错误永远是「口令错」。但头部字节是 AES-GCM 的 AAD，
 * 所以改迭代数、改时间戳、改 kdf 名都会被 tag 校验抓到（这是挡住「降迭代数攻击」的机制）。
 *
 * 文件布局：`magic(8) | headerLength(4, big-endian) | headerJson | ciphertext | tag(16)`。
 */
data class ArchiveHeader(
    val version: Int = CURRENT_VERSION,
    val kdf: String = ArchiveKdf.PBKDF2_SHA256_NAME,
    val iterations: Int = ArchiveKdf.DEFAULT_ITERATIONS,
    val saltBase64: String = "",
    val nonceBase64: String = "",
    val createdAtEpochMs: Long = 0L,
) {
    companion object {
        const val CURRENT_MAGIC: String = "MHSYNCA1"
        const val CURRENT_VERSION: Int = 1

        /** GCM nonce 字节数；每写一个归档都必须换新的，绝不复用。 */
        const val NONCE_SIZE: Int = 12

        /** GCM 认证标签字节数（128 位）。 */
        const val TAG_SIZE: Int = 16

        /** 头部 JSON 上限：只是防止畸形文件把内存吃光，正常头部约 160 字节。 */
        const val MAX_HEADER_BYTES: Int = 4096

        /** 明文载荷上限 8 MiB，与同步信封的传输上限同量级。 */
        const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024
    }
}

/**
 * 头部 JSON 编解码。序列化**字段顺序固定、无空白**，因为读端认证的是盘上原字节，
 * 而写端与 Windows 必须产出同一串字节（否则同一归档在两端算出的 AAD 不同）。
 */
object ArchiveHeaderCodec {

    /** 固定序：version, kdf, iterations, saltBase64, nonceBase64, createdAtEpochMs。 */
    fun serialize(header: ArchiveHeader): ByteArray {
        val json = buildJsonObject {
            put("version", header.version)
            put("kdf", header.kdf)
            put("iterations", header.iterations)
            put("saltBase64", header.saltBase64)
            put("nonceBase64", header.nonceBase64)
            put("createdAtEpochMs", header.createdAtEpochMs)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** 解析头部；任何结构问题都返回 null（调用方翻译成 [ArchiveError.CORRUPT_HEADER]）。 */
    fun tryParse(headerBytes: ByteArray?): ArchiveHeader? {
        if (headerBytes == null || headerBytes.isEmpty()) return null
        val root = try {
            Json.parseToJsonElement(headerBytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            return null
        }
        val json = root as? JsonObject ?: return null
        // 转义的孤立代理项：kotlinx.serialization 会收下（得到 [D800] 这样的码元序列），
        // System.Text.Json 在 JsonDocument.Parse 阶段就抛 —— 两端必须都判 corrupt-header，
        // 否则同一份头部在两端一个能读一个不能读。
        if (WireText.containsLoneSurrogate(json)) return null
        val version = json.intOf("version") ?: return null
        val iterations = json.intOf("iterations") ?: return null
        val createdAtEpochMs = json.longOf("createdAtEpochMs") ?: return null
        val kdf = json.stringOf("kdf") ?: return null
        val saltBase64 = json.stringOf("saltBase64") ?: return null
        val nonceBase64 = json.stringOf("nonceBase64") ?: return null
        return ArchiveHeader(
            version = version,
            kdf = kdf,
            iterations = iterations,
            saltBase64 = saltBase64,
            nonceBase64 = nonceBase64,
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun parse(headerBytes: ByteArray?): ArchiveHeader =
        tryParse(headerBytes) ?: throw ArchiveException(
            ArchiveError.CORRUPT_HEADER,
            "The archive header is not a readable header object.",
        )

    private fun JsonObject.intOf(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.longOf(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    private fun JsonObject.stringOf(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }
}
