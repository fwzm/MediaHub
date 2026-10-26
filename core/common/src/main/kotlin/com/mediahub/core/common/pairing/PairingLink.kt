package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 配对码结果：成功时 payload 非空且 reason 为空；失败时 payload 为空、reason 是稳定码。
 *
 * Windows 用「结果对象 + Reason 属性」表达同一件事；reasonOrOk 只是让日志/测试能直接打出 "ok"。
 */
data class PairingCodeResult(val payload: PairingPayload?, val reason: String?) {

    /** 是否成功。 */
    val isSuccess: Boolean
        get() = payload != null && reason == null

    /** 成功时返回 "ok"，失败时返回原因码（便于和 Windows 生成器输出对照）。 */
    val reasonOrOk: String
        get() = reason ?: "ok"

    companion object {
        fun ok(payload: PairingPayload): PairingCodeResult = PairingCodeResult(payload, null)

        fun failed(reason: String): PairingCodeResult = PairingCodeResult(null, reason)
    }
}

/**
 * 配对码：载荷 JSON 的 base64url 文本，无 '=' 填充（ADR-S003 D2）。
 *
 * 与 Windows PairingCode.cs 逐字对齐：
 * - 规范化只剥空格 / Tab / CR / LF，不做其它容错；
 * - 长度上限 4096 字符，超出是 code-too-long；
 * - 字符必须属于 base64url 字母表（字母数字 + - + _），出现 '=' 或 '!' 等是 not-base64url；
 * - 解码后必须是严格合法 UTF-8，否则是 payload-invalid；
 * - 内部载荷的契约错误按 Windows MapPayloadReason 映射：五个业务码透传，其余一律 payload-invalid；
 * - 最后校验时效，过期是 expired。
 */
object PairingCode {

    /** 配对码长度上限（字符）。 */
    const val MAXIMUM_LENGTH: Int = 4096

    /** 成功码字面值，仅用于与 Windows 输出对照。 */
    const val OK: String = "ok"

    private const val CODE_PREFIX_LENGTH: Int = 1

    /**
     * 编码：UTF-8(载荷 JSON) → base64url（无填充）。
     */
    fun encode(payload: PairingPayload): String {
        val jsonBytes = PairingPayloadCodec.encode(payload).toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
    }

    /**
     * 规范化：只剥空格 / Tab / CR / LF（扫码与手输时会被插入换行）。
     */
    fun normalize(code: String?): String {
        if (code == null) return ""
        val builder = StringBuilder(code.length)
        for (character in code) {
            when (character) {
                ' ', '\t', '\r', '\n' -> Unit
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    /**
     * 解码 + 校验；不抛异常，失败折成 PairingCodeResult。
     */
    fun decode(code: String?, nowEpochMs: Long): PairingCodeResult {
        val normalized = normalize(code)
        if (normalized.isEmpty()) {
            return PairingCodeResult.failed(PairingCodeReasons.EMPTY)
        }
        if (normalized.length > MAXIMUM_LENGTH) {
            return PairingCodeResult.failed(PairingCodeReasons.TOO_LONG)
        }
        for (character in normalized) {
            if (!isBase64UrlCharacter(character)) {
                return PairingCodeResult.failed(PairingCodeReasons.NOT_BASE64URL)
            }
        }
        if (normalized.length % 4 == CODE_PREFIX_LENGTH) {
            return PairingCodeResult.failed(PairingCodeReasons.NOT_BASE64URL)
        }
        val bytes: ByteArray = try {
            base64UrlDecode(normalized)
        } catch (e: IllegalArgumentException) {
            return PairingCodeResult.failed(PairingCodeReasons.NOT_BASE64URL)
        }
        val json = decodeStrictUtf8(bytes) ?: return PairingCodeResult.failed(PairingCodeReasons.PAYLOAD_INVALID)
        val decoded = PairingPayloadCodec.tryDecode(json)
        if (decoded is PairingPayloadCodec.DecodeResult.Failed) {
            return PairingCodeResult.failed(mapPayloadReason(decoded.reason))
        }
        val payload = (decoded as PairingPayloadCodec.DecodeResult.Ok).payload
        return try {
            payload.validateFor(nowEpochMs)
            PairingCodeResult.ok(payload)
        } catch (e: PairingContractException) {
            PairingCodeResult.failed(mapPayloadReason(e.reason))
        }
    }

    /**
     * 载荷契约理由 → 配对码理由。五个业务码透传，其余（malformed-json / missing-field:* /
     * invalid-field:*）一律折成 payload-invalid，与 Windows MapPayloadReason 一致。
     */
    fun mapPayloadReason(reason: String): String = when (reason) {
        PairingReasons.UNSUPPORTED_VERSION,
        PairingReasons.BAD_CHALLENGE,
        PairingReasons.BAD_FINGERPRINT,
        PairingReasons.BAD_URL,
        PairingReasons.EXPIRED,
        -> reason
        else -> PairingCodeReasons.PAYLOAD_INVALID
    }

    /** base64url 字母表判定（不接受 '=' 填充）。 */
    fun isBase64UrlCharacter(character: Char): Boolean =
        (character in 'A'..'Z') || (character in 'a'..'z') || (character in '0'..'9') ||
            character == '-' || character == '_'

    private fun base64UrlDecode(text: String): ByteArray {
        val remainder = text.length % 4
        val padded = if (remainder == 0) text else text + "=".repeat(4 - remainder)
        return Base64.getUrlDecoder().decode(padded)
    }

    /** 严格 UTF-8 解码；非法字节序列返回 null（不静默替换）。 */
    private fun decodeStrictUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

/** 配对链接解析结果（结构与 PairingCodeResult 相同：成功有 payload，失败有 reason）。 */
data class PairingLinkResult(val payload: PairingPayload?, val reason: String?) {

    val isSuccess: Boolean
        get() = payload != null && reason == null

    val reasonOrOk: String
        get() = reason ?: "ok"

    companion object {
        fun ok(payload: PairingPayload): PairingLinkResult = PairingLinkResult(payload, null)

        fun failed(reason: String): PairingLinkResult = PairingLinkResult(null, reason)
    }
}

/**
 * 配对链接：mediahub://pair?<配对码>（ADR-S003 D1/D2）。
 *
 * 与 Windows PairingLink.cs 逐字对齐：
 * - 前缀是 "mediahub://pair?"，大小写不敏感；create 生成的链接不写 code= 参数名；
 * - 解析时也接受 mediahub://pair?code=<配对码>（Windows 生成的码可能被二次包装）；
 * - trim 后空 → pairing-link-empty；前缀不符 → not-a-pairing-link；超过 8192 字符 → pairing-link-too-long；
 * - 其余错误由 PairingCode 决定（含 expired 等载荷码）。
 */
object PairingLink {

    /** 链接前缀。 */
    const val PREFIX: String = "mediahub://pair?"

    /** 参数名（解析时可选）。 */
    const val PARAMETER_NAME: String = "code"

    /** 链接长度上限（字符）。 */
    const val MAXIMUM_LENGTH: Int = 8192

    private const val PARAMETER_PREFIX: String = "code="

    /** 由载荷生成链接。 */
    fun create(payload: PairingPayload): String = PREFIX + PairingCode.encode(payload)

    /**
     * 取出链接里的配对码；不是配对链接时返回空串。
     */
    fun extractCode(link: String?): String {
        if (link == null) return ""
        val trimmed = WireText.trim(link)
        if (!WireText.startsWithAsciiIgnoreCase(trimmed, PREFIX)) return ""
        var rest = WireText.trim(trimmed.substring(PREFIX.length))
        if (WireText.startsWithAsciiIgnoreCase(rest, PARAMETER_PREFIX)) {
            rest = rest.substring(PARAMETER_PREFIX.length)
        }
        return WireText.trim(rest)
    }

    /**
     * 解析链接；不抛异常，失败折成 PairingLinkResult。
     */
    fun parse(link: String?, nowEpochMs: Long): PairingLinkResult {
        val raw = link ?: return PairingLinkResult.failed(PairingLinkReasons.EMPTY)
        if (WireText.isBlank(raw)) return PairingLinkResult.failed(PairingLinkReasons.EMPTY)
        val trimmed = WireText.trim(raw)
        if (!WireText.startsWithAsciiIgnoreCase(trimmed, PREFIX)) {
            return PairingLinkResult.failed(PairingLinkReasons.NOT_A_LINK)
        }
        if (trimmed.length > MAXIMUM_LENGTH) {
            return PairingLinkResult.failed(PairingLinkReasons.TOO_LONG)
        }
        val decoded = PairingCode.decode(extractCode(trimmed), nowEpochMs)
        val payload = decoded.payload
            ?: return PairingLinkResult.failed(decoded.reason ?: PairingCodeReasons.PAYLOAD_INVALID)
        return PairingLinkResult.ok(payload)
    }
}
