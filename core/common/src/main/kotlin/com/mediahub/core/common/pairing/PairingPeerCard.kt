package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 对端在配对握手之后自报的一张卡片（W6-71）。
 *
 * 为什么需要它：传输帧里只有邀请，不含对端身份。没有卡片，本机就永远记不住「刚连上的是谁」，
 * 配对设备清单也就只能是空表。卡片里的指纹是**对端自报**的首次使用钉扎锚点，不是身份证明：
 * 它能证明「这次的对方和上次是不是同一个自称」，不能证明「对方是谁」。
 *
 * 与 Windows `MediaHub.Sync.PairingPeerCard` 逐字段对齐：
 * - [schemaVersion] 固定 1，其它值一律 card-unsupported-version；
 * - [deviceId] 与 [deviceFingerprint] 必填；[deviceFingerprint] 读入后规范化为小写 hex；
 * - [displayName] 只作展示，读入时剥空白并截断到 [MAXIMUM_TEXT_LENGTH]；
 * - [sourceCount] 只作展示，负数/非数字一律当 0。
 */
data class PairingPeerCard(
    /** 卡片形状版本；本构建只认 [CURRENT_SCHEMA_VERSION]。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 对端自称的稳定标识。 */
    val deviceId: String,
    /** 对端希望本机钉住的小写 hex 指纹（读入时已规范化）。 */
    val deviceFingerprint: String,
    /** 对端的人类可读名字；没有时为空串。 */
    val displayName: String = "",
    /** 对端自报的来源数量，仅作展示。 */
    val sourceCount: Int = 0,
) {

    /** 把这张卡片变成一次登记输入（时间用调用方的钟，卡里不带时间）。 */
    fun toObservation(nowEpochMs: Long): PairedDeviceObservation = PairedDeviceObservation(
        deviceId = deviceId,
        fingerprint = deviceFingerprint,
        displayName = displayName,
        sourceCount = sourceCount,
        nowEpochMs = nowEpochMs,
    )

    companion object {
        /** 本构建写出并接受的卡片版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** 标识与名字的字符上限。 */
        const val MAXIMUM_TEXT_LENGTH: Int = 128
    }
}

/**
 * 卡片被拒绝时的稳定原因码。
 *
 * 这 6 个字符串与 Windows `PairingPeerCardReasons` 同名、同拼写、同顺序：它们是跨语言契约，
 * 不许改名、不许增删（要加新码必须两端同名同序并各有用例）。
 */
object PairingPeerCardReasons {
    const val MALFORMED_JSON: String = "card-malformed-json"
    const val TOO_LONG: String = "card-too-long"
    const val UNSUPPORTED_VERSION: String = "card-unsupported-version"
    const val MISSING_DEVICE_ID: String = "card-missing-device-id"
    const val MISSING_FINGERPRINT: String = "card-missing-fingerprint"
    const val BAD_FINGERPRINT: String = "card-bad-fingerprint"

    /** 与 Windows `PairingPeerCardReasons.All` 同序。 */
    val ALL: List<String> = listOf(
        MALFORMED_JSON,
        TOO_LONG,
        UNSUPPORTED_VERSION,
        MISSING_DEVICE_ID,
        MISSING_FINGERPRINT,
        BAD_FINGERPRINT,
    )
}

/**
 * 卡片的字节层：写出与 Windows `PairingPeerCardCodec.Serialize` 逐字节相同的紧凑 JSON，并读回同一张卡。
 *
 * 为什么手工拼 JSON：Windows 用 Utf8JsonWriter 的默认 encoder，会把 8 个特殊 ASCII 与全部非 ASCII
 * 写成大写 uXXXX 转义，而 kotlinx.serialization 原样输出。两端互相交换的是同一串字节，这些差异必须
 * 在这里抹平，所以字符串转义复用 [PairedDeviceDocument.quote]。
 *
 * 读端判定序与 Windows 逐条同序（先形状、再版本、再字段、最后指纹），任何一步失败只给稳定码，
 * 绝不把对端自选的名字回显进日志或异常消息。
 */
object PairingPeerCardCodec {

    /** 卡片很小；超过这个长度的一定不是卡片，在解析之前就拒绝。 */
    const val MAXIMUM_LENGTH: Int = 2048

    /** 写出紧凑 JSON，字段序固定（与 Windows 相同）。 */
    fun serialize(card: PairingPeerCard): String {
        val builder = StringBuilder(160)
        builder.append('{')
        appendField(builder, "schemaVersion", card.schemaVersion.toString())
        appendField(builder, "deviceId", PairedDeviceDocument.quote(card.deviceId))
        appendField(builder, "deviceFingerprint", PairedDeviceDocument.quote(card.deviceFingerprint))
        appendField(builder, "displayName", PairedDeviceDocument.quote(card.displayName))
        appendField(builder, "sourceCount", card.sourceCount.toString())
        builder.append('}')
        return builder.toString()
    }

    /** 追加一个字段：名字按 Utf8JsonWriter 规则引用，值已由调用方编码好。 */
    private fun appendField(builder: StringBuilder, name: String, encodedValue: String) {
        if (builder.length > 1) {
            builder.append(',')
        }
        builder.append(PairedDeviceDocument.quote(name)).append(':').append(encodedValue)
    }

    /** 解析结果：成功给卡片，失败只给稳定码。 */
    sealed interface DecodeResult {
        class Ok(val card: PairingPeerCard) : DecodeResult
        class Failed(val reason: String) : DecodeResult
    }

    /** 解析并校验一张卡片；不抛异常。 */
    fun tryDeserialize(json: String?): DecodeResult {
        if (json == null || WireText.isBlank(json)) {
            return DecodeResult.Failed(PairingPeerCardReasons.MALFORMED_JSON)
        }
        if (json.length > MAXIMUM_LENGTH) {
            return DecodeResult.Failed(PairingPeerCardReasons.TOO_LONG)
        }
        val element = try {
            Json.parseToJsonElement(json)
        } catch (error: Exception) {
            return DecodeResult.Failed(PairingPeerCardReasons.MALFORMED_JSON)
        }
        val root = element as? JsonObject
            ?: return DecodeResult.Failed(PairingPeerCardReasons.MALFORMED_JSON)
        // 转义出来的孤立代理项会让 .NET GetString() 抛非契约异常；两端统一在文档级判成 malformed-json。
        if (WireText.containsLoneSurrogate(root)) {
            return DecodeResult.Failed(PairingPeerCardReasons.MALFORMED_JSON)
        }
        val version = intOf(root, "schemaVersion")
            ?: return DecodeResult.Failed(PairingPeerCardReasons.MALFORMED_JSON)
        if (version != PairingPeerCard.CURRENT_SCHEMA_VERSION) {
            return DecodeResult.Failed(PairingPeerCardReasons.UNSUPPORTED_VERSION)
        }
        val deviceId = stringOf(root, "deviceId")
            ?: return DecodeResult.Failed(PairingPeerCardReasons.MISSING_DEVICE_ID)
        if (deviceId.length > PairingPeerCard.MAXIMUM_TEXT_LENGTH) {
            return DecodeResult.Failed(PairingPeerCardReasons.MISSING_DEVICE_ID)
        }
        val fingerprintText = stringOf(root, "deviceFingerprint")
            ?: return DecodeResult.Failed(PairingPeerCardReasons.MISSING_FINGERPRINT)
        val fingerprint = PairingFingerprint.tryParse(fingerprintText)
            ?: return DecodeResult.Failed(PairingPeerCardReasons.BAD_FINGERPRINT)
        // 与 C# 的 .Trim() 同义（WireText.trim 就是那 25 个 Unicode White_Space 码点）。
        var displayName = WireText.trim(stringOfAllowEmpty(root, "displayName") ?: "")
        if (displayName.length > PairingPeerCard.MAXIMUM_TEXT_LENGTH) {
            displayName = displayName.substring(0, PairingPeerCard.MAXIMUM_TEXT_LENGTH)
        }
        val rawCount = intOf(root, "sourceCount")
        val sourceCount = if (rawCount != null && rawCount > 0) rawCount else 0
        return DecodeResult.Ok(
            PairingPeerCard(
                schemaVersion = version,
                deviceId = deviceId,
                deviceFingerprint = fingerprint.hex,
                displayName = displayName,
                sourceCount = sourceCount,
            )
        )
    }

    /** 取整数：必须是 JSON number 且落在 Int 范围；字符串不算（与 .NET TryGetInt32 同义）。 */
    private fun intOf(root: JsonObject, name: String): Int? {
        val primitive = root[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    /** 取必填字符串：必须存在、是字符串；剥空白后为空算缺失。 */
    private fun stringOf(root: JsonObject, name: String): String? {
        val raw = stringOfAllowEmpty(root, name) ?: return null
        val trimmed = WireText.trim(raw)
        return if (trimmed.isEmpty()) null else trimmed
    }

    /** 取可选字符串：不是字符串或缺失都当没有。 */
    private fun stringOfAllowEmpty(root: JsonObject, name: String): String? {
        val primitive = root[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }
}
