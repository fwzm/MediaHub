package com.mediahub.core.common.pairing

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 配对帧：4 字节大端 int32 长度 + 载荷字节。
 *
 * 与 Windows PairingFrame.cs 逐字对齐（注意：合同里**没有** magic、也**没有**帧内版本字段，
 * 版本只在载荷的 schemaVersion 里）：
 * - 载荷必须 1..8192 字节；空载荷或超长在写端是编程错误（IllegalArgumentException）；
 * - 读端遇到 length <= 0、length > 8192、或字节不足，一律返回 null，且**不为超长长度分配内存**；
 * - null 在传输层映射为 PairingTransportReasons.PAYLOAD_INVALID。
 */
object PairingFrame {

    /** 载荷上限，字节。 */
    const val MAXIMUM_PAYLOAD_BYTES: Int = 8192

    /** 长度头字节数。 */
    const val HEADER_BYTES: Int = 4

    /**
     * 编码一帧。空载荷或超过上限抛 IllegalArgumentException。
     */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "配对帧载荷不能为空" }
        require(payload.size <= MAXIMUM_PAYLOAD_BYTES) {
            "配对帧载荷不能超过 " + MAXIMUM_PAYLOAD_BYTES + " 字节，实际 " + payload.size
        }
        val out = ByteArrayOutputStream(HEADER_BYTES + payload.size)
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    /** UTF-8 文本编码一帧。 */
    fun encodeText(text: String): ByteArray =
        encode(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * 解码一帧；任何结构问题返回 null。
     */
    fun decodeOrNull(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        if (bytes.size < HEADER_BYTES) return null
        val length = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        if (length <= 0 || length > MAXIMUM_PAYLOAD_BYTES) return null
        if (bytes.size < HEADER_BYTES + length) return null
        return bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
    }

    /**
     * 解码一帧为 UTF-8 文本；结构问题返回 null。非法 UTF-8 字节会被替换，不在此处判错
     * （载荷契约会把非法文本判成 malformed-json）。
     */
    fun decodeTextOrNull(bytes: ByteArray?): String? {
        val payload = decodeOrNull(bytes) ?: return null
        return String(payload, StandardCharsets.UTF_8)
    }
}
