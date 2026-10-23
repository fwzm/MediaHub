package com.mediahub.core.common.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI

/**
 * 配对邀请载荷：二维码里真正承载内容的结构（ADR-S003 D2）。
 *
 * 与 Windows PairingPayload.cs 逐字对齐：
 * - schemaVersion 只能是 1，其它值（含未来版本）一律 unsupported-version；
 * - ephemeralUrl 必须是绝对 http/https、必须有 host、不得带 user-info、不得带 query/fragment；
 * - challenge 32 字节规范 base64，指纹 32 字节小写 hex；
 * - 生存期 120 秒，ExpiresAtEpochMs <= now 即过期（等于也算过期）；
 * - toString 必须脱敏 challenge：日志里出现 challenge 等于泄漏一次性凭据。
 *
 * 该结构只描述「怎么连、连谁」；真实 TLS 连接与凭据交换不在本类职责内。
 */
data class PairingPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val deviceId: String = "",
    val ephemeralUrl: String = "",
    val challenge: PairingChallenge = PairingChallenge.EMPTY,
    val publicKeyFingerprint: PairingFingerprint = PairingFingerprint.EMPTY,
    val expiresAtEpochMs: Long = 0L,
) {

    /**
     * 只校验结构（不看时间）。失败抛 PairingContractException，reason 与 Windows 一致。
     */
    fun validateShape() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw PairingContractException.unsupportedVersion(schemaVersion, CURRENT_SCHEMA_VERSION)
        }
        if (deviceId.isBlank()) {
            throw PairingContractException.missingField("deviceId")
        }
        validateEphemeralUrl()
        if (challenge.isEmpty) {
            throw PairingContractException.badChallenge("challenge 为空")
        }
        if (publicKeyFingerprint.isEmpty) {
            throw PairingContractException.badFingerprint("公钥指纹为空")
        }
        if (expiresAtEpochMs <= 0L) {
            throw PairingContractException.invalidField("expiresAtEpochMs", "必须为正整数毫秒")
        }
    }

    /**
     * 结构 + 时效校验。过期判定是 ExpiresAtEpochMs <= now（等于也过期）。
     */
    fun validateFor(nowEpochMs: Long) {
        validateShape()
        if (expiresAtEpochMs <= nowEpochMs) {
            throw PairingContractException.expired(expiresAtEpochMs, nowEpochMs)
        }
    }

    /** 剩余毫秒；已过期返回 0。 */
    fun remainingMilliseconds(nowEpochMs: Long): Long {
        val remaining = expiresAtEpochMs - nowEpochMs
        return if (remaining <= 0L) 0L else remaining
    }

    override fun toString(): String =
        "PairingPayload(v" + schemaVersion + ", " + deviceId + ", " + ephemeralUrl +
            ", challenge=<redacted>, " + publicKeyFingerprint.hex +
            ", expires=" + expiresAtEpochMs + ")"

    private fun validateEphemeralUrl() {
        val raw = ephemeralUrl
        if (raw.isBlank()) {
            throw PairingContractException.missingField("ephemeralUrl")
        }
        val trimmed = raw.trim()
        val uri: URI = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw PairingContractException.badUrl("临时地址不是合法 URI")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw PairingContractException.badUrl("临时地址必须是 http/https")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            throw PairingContractException.badUrl("临时地址必须有 host")
        }
        val userInfo = uri.userInfo
        if (!userInfo.isNullOrEmpty()) {
            throw PairingContractException.badUrl("临时地址不得带 user-info")
        }
        if (!uri.rawQuery.isNullOrEmpty()) {
            throw PairingContractException.badUrl("临时地址不得带 query")
        }
        if (!uri.rawFragment.isNullOrEmpty()) {
            throw PairingContractException.badUrl("临时地址不得带 fragment")
        }
    }

    companion object {
        /** 唯一可读的载荷版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** 邀请生存期，秒。 */
        const val LIFETIME_SECONDS: Int = 120

        /** 邀请生存期，毫秒。 */
        const val LIFETIME_MILLISECONDS: Long = 120_000L

        /**
         * 按 Windows PairingPayload.Create 构造：expiresAt = now + 120000，然后校验结构。
         */
        fun create(
            deviceId: String,
            ephemeralUrl: String,
            challenge: PairingChallenge,
            publicKeyFingerprint: PairingFingerprint,
            nowEpochMs: Long,
        ): PairingPayload {
            val payload = PairingPayload(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                deviceId = deviceId,
                ephemeralUrl = ephemeralUrl,
                challenge = challenge,
                publicKeyFingerprint = publicKeyFingerprint,
                expiresAtEpochMs = nowEpochMs + LIFETIME_MILLISECONDS,
            )
            payload.validateShape()
            return payload
        }
    }
}

/**
 * 配对载荷的线格式编解码。字段顺序与 Windows PairingPayloadCodec 完全一致：
 * schemaVersion, deviceId, ephemeralUrl, challenge, publicKeyFingerprint, expiresAtEpochMs。
 *
 * 读取时未知属性忽略（不报错、不回写）；指纹与 challenge 一律规范化（小写 hex / 规范 base64）。
 */
object PairingPayloadCodec {

    /** 序列化；写前先校验结构。 */
    fun encode(payload: PairingPayload): String {
        payload.validateShape()
        val json: JsonObject = buildJsonObject {
            put("schemaVersion", payload.schemaVersion)
            put("deviceId", payload.deviceId)
            put("ephemeralUrl", payload.ephemeralUrl)
            put("challenge", payload.challenge.base64)
            put("publicKeyFingerprint", payload.publicKeyFingerprint.hex)
            put("expiresAtEpochMs", payload.expiresAtEpochMs)
        }
        return json.toString()
    }

    /**
     * 反序列化；任何违规抛 PairingContractException（reason 逐字对齐 Windows）。
     */
    fun decode(text: String): PairingPayload {
        if (text.isBlank()) {
            throw PairingContractException.malformedJson("配对载荷为空")
        }
        val root: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw PairingContractException.malformedJson("不是合法 JSON")
        }
        if (root !is JsonObject) {
            throw PairingContractException.malformedJson("根节点不是 JSON 对象")
        }
        val schemaVersion = readRequiredInt32(root, "schemaVersion")
        if (schemaVersion != PairingPayload.CURRENT_SCHEMA_VERSION) {
            throw PairingContractException.unsupportedVersion(schemaVersion, PairingPayload.CURRENT_SCHEMA_VERSION)
        }
        val deviceId = readRequiredString(root, "deviceId")
        val ephemeralUrl = readRequiredString(root, "ephemeralUrl")
        val challengeText = readRequiredString(root, "challenge")
        val fingerprintText = readRequiredString(root, "publicKeyFingerprint")
        val expiresAtEpochMs = readRequiredInt64(root, "expiresAtEpochMs")
        val payload = PairingPayload(
            schemaVersion = schemaVersion,
            deviceId = deviceId,
            ephemeralUrl = ephemeralUrl,
            challenge = PairingChallenge.parse(challengeText),
            publicKeyFingerprint = PairingFingerprint.parse(fingerprintText),
            expiresAtEpochMs = expiresAtEpochMs,
        )
        payload.validateShape()
        return payload
    }

    /** 反序列化但把契约错误折成结果，供 UI/日志使用。 */
    fun tryDecode(text: String): DecodeResult =
        try {
            DecodeResult.Ok(decode(text))
        } catch (e: PairingContractException) {
            DecodeResult.Failed(e.reason, e.message ?: "")
        }

    /** 解码结果。 */
    sealed interface DecodeResult {
        data class Ok(val payload: PairingPayload) : DecodeResult
        data class Failed(val reason: String, val message: String) : DecodeResult
    }

    private fun valueOf(owner: JsonObject, name: String): JsonElement? {
        val element = owner[name] ?: return null
        if (element is JsonNull) return null
        return element
    }

    private fun readRequiredString(owner: JsonObject, name: String): String {
        val element = valueOf(owner, name) ?: throw PairingContractException.missingField(name)
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw PairingContractException.invalidField(name, "必须是字符串")
        }
        if (primitive.content.isBlank()) {
            throw PairingContractException.missingField(name)
        }
        return primitive.content
    }

    private fun readRequiredInt32(owner: JsonObject, name: String): Int {
        val element = valueOf(owner, name)
            ?: throw PairingContractException.invalidField(name, "必须是 32 位整数")
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            throw PairingContractException.invalidField(name, "必须是 32 位整数")
        }
        return primitive.intOrNull
            ?: throw PairingContractException.invalidField(name, "必须是 32 位整数")
    }

    private fun readRequiredInt64(owner: JsonObject, name: String): Long {
        val element = valueOf(owner, name)
            ?: throw PairingContractException.invalidField(name, "必须是 64 位整数")
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            throw PairingContractException.invalidField(name, "必须是 64 位整数")
        }
        return primitive.longOrNull
            ?: throw PairingContractException.invalidField(name, "必须是 64 位整数")
    }
}
