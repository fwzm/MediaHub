package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 配对设备文档的字节层：把 [PairedDevice] 清单写成与 Windows 逐字节相同的 JSON，并读回同一份清单。
 *
 * 为什么不用 kotlinx.serialization 直接序列化：Windows 侧用 System.Text.Json 的 Utf8JsonWriter（Indented），
 * 它把 CRLF 当换行、把非 ASCII 与 `<` `>` `&` `+` `'` 反引号一律写成大写 \uXXXX、且不写尾随换行。
 * 两端读写同一个 paired-devices.json，这些字节差异会让同一份清单在两边「看起来不同」，所以这里手工复刻那套编码。
 *
 * 与 Windows 侧 MediaHub.Sync.FilePairedDeviceStore 的 PersistLocked/ReadDevice 对应。
 */
internal object PairedDeviceDocument {

    /** 本构建写出并接受的文档版本。 */
    const val CURRENT_VERSION: Int = 1

    /** 缩进两个空格（Utf8JsonWriter 的 Indented 默认）。 */
    private const val INDENT = "  "

    /** 行分隔：Utf8JsonWriter 在 Windows 上写 CRLF，两端要逐字节相同就必须钉死。 */
    private const val NEWLINE = "\r\n"

    private const val HEX_UPPER = "0123456789ABCDEF"

    /** 渲染整份文档，末尾不带换行。 */
    fun render(devices: List<PairedDevice>): String {
        // 与 Windows 相同的两级序：先 ASCII 大小写不敏感，再逐码元。用 WireText 而不是
        // String.CASE_INSENSITIVE_ORDER：Java 会把 U+017F/U+212A 折成 ASCII，.NET OrdinalIgnoreCase 不会。
        val ordered = devices.sortedWith { left, right ->
            val byName = WireText.compareAsciiIgnoreCase(left.deviceId, right.deviceId)
            if (byName != 0) byName else left.deviceId.compareTo(right.deviceId)
        }
        val builder = StringBuilder()
        builder.append('{').append(NEWLINE)
        builder.append(INDENT).append("\"version\": ").append(CURRENT_VERSION).append(',').append(NEWLINE)
        if (ordered.isEmpty()) {
            builder.append(INDENT).append("\"devices\": []").append(NEWLINE)
        } else {
            builder.append(INDENT).append("\"devices\": [").append(NEWLINE)
            ordered.forEachIndexed { index, device ->
                builder.append(INDENT).append(INDENT).append('{').append(NEWLINE)
                appendField(builder, 3, "deviceId", device.deviceId, quote = true)
                appendField(builder, 3, "displayName", device.displayName, quote = true)
                appendField(builder, 3, "fingerprint", device.fingerprint, quote = true)
                appendField(builder, 3, "pairedAtEpochMs", device.pairedAtEpochMs.toString(), quote = false)
                appendField(builder, 3, "lastSeenEpochMs", device.lastSeenEpochMs.toString(), quote = false)
                appendField(builder, 3, "sourceCount", device.sourceCount.toString(), quote = false, last = true)
                builder.append(repeat(INDENT, 2)).append('}')
                if (index != ordered.lastIndex) {
                    builder.append(',')
                }
                builder.append(NEWLINE)
            }
            builder.append(INDENT).append(']').append(NEWLINE)
        }
        builder.append('}')
        return builder.toString()
    }

    private fun appendField(builder: StringBuilder, depth: Int, name: String, value: String, quote: Boolean, last: Boolean = false) {
        builder.append(repeat(INDENT, depth)).append('"').append(name).append("\": ")
        builder.append(if (quote) quote(value) else value)
        if (!last) {
            builder.append(',')
        }
        builder.append(NEWLINE)
    }

    private fun repeat(text: String, times: Int): String {
        val builder = StringBuilder(text.length * times)
        repeat(times) { builder.append(text) }
        return builder.toString()
    }

    /**
     * Utf8JsonWriter（默认 encoder）的字符串编码规则：
     * 只有 8 个 ASCII 字符会被转义，控制字符用短转义或大写 \u00XX，其余一切非 ASCII（含代理对）写成大写 \uXXXX。
     */
    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\u0022")
                '&' -> builder.append("\\u0026")
                '\'' -> builder.append("\\u0027")
                '+' -> builder.append("\\u002B")
                '<' -> builder.append("\\u003C")
                '>' -> builder.append("\\u003E")
                '\\' -> builder.append("\\\\")
                '`' -> builder.append("\\u0060")
                '\b' -> builder.append("\\b")
                '\t' -> builder.append("\\t")
                '\n' -> builder.append("\\n")
                '\u000C' -> builder.append("\\f")
                '\r' -> builder.append("\\r")
                else -> {
                    if (character.code < 0x20 || character.code >= 0x7F) {
                        builder.append("\\u")
                        builder.append(HEX_UPPER[(character.code shr 12) and 0xF])
                        builder.append(HEX_UPPER[(character.code shr 8) and 0xF])
                        builder.append(HEX_UPPER[(character.code shr 4) and 0xF])
                        builder.append(HEX_UPPER[character.code and 0xF])
                    } else {
                        builder.append(character)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /** 一份文档读出来的两种结果：要么一份清单，要么一个稳定原因码（调用方据此隔离文件）。 */
    sealed interface Decoded {
        /** 文档本身读得懂；单行坏掉的设备已被跳过。 */
        data class Loaded(val devices: List<PairedDevice>) : Decoded

        /** 整份文档不可信，必须原样隔离，绝不能被覆盖。 */
        data class Rejected(val reason: String) : Decoded
    }

    /** 文档超过这个长度就不是本应用的文档：隔离不读。与 Windows MaxDocumentChars 相同（UTF-16 码元数）。 */
    const val MAXIMUM_DOCUMENT_CHARS: Long = 1L * 1024 * 1024

    /**
     * 读回一份文档。判定顺序与 Windows FilePairedDeviceStore.LoadLocked 逐条对应：
     * 空白 → 空清单；超长 / 不是 JSON / 根不是对象 / 没有整数 version / 没有 devices 数组 → malformed-json；
     * version 不认识 → unsupported-version:<n>；单行坏掉只跳过该行。
     */
    fun decode(text: String): Decoded {
        if (WireText.isBlank(text)) {
            return Decoded.Loaded(emptyList())
        }
        if (text.length > MAXIMUM_DOCUMENT_CHARS) {
            return Decoded.Rejected(PairedDeviceStoreReasons.MALFORMED_JSON)
        }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (exception: Exception) {
            return Decoded.Rejected(PairedDeviceStoreReasons.MALFORMED_JSON)
        }
        if (root !is JsonObject) {
            return Decoded.Rejected(PairedDeviceStoreReasons.MALFORMED_JSON)
        }
        val versionElement = root["version"]
        val version = (versionElement as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version == null) {
            return Decoded.Rejected(PairedDeviceStoreReasons.MALFORMED_JSON)
        }
        if (version != CURRENT_VERSION) {
            return Decoded.Rejected(PairedDeviceStoreReasons.UNSUPPORTED_VERSION + ":" + version)
        }
        val devicesElement = root["devices"]
        if (devicesElement !is JsonArray) {
            return Decoded.Rejected(PairedDeviceStoreReasons.MALFORMED_JSON)
        }
        val devices = mutableListOf<PairedDevice>()
        for (element in devicesElement) {
            val device = readDevice(element) ?: continue
            if (indexOf(devices, device.deviceId) >= 0) {
                continue
            }
            devices.add(device)
        }
        return Decoded.Loaded(devices)
    }

    /** 单行设备；读不懂就返回 null（跳过而不是整份失败）。 */
    private fun readDevice(element: JsonElement): PairedDevice? {
        if (element !is JsonObject) {
            return null
        }
        val idElement = element["deviceId"]
        if (idElement !is JsonPrimitive || !idElement.isString) {
            return null
        }
        val deviceId = WireText.trim(idElement.content)
        if (deviceId.isEmpty()) {
            return null
        }
        val fingerprintElement = element["fingerprint"]
        if (fingerprintElement !is JsonPrimitive || !fingerprintElement.isString) {
            return null
        }
        val fingerprint = PairingFingerprint.tryParse(fingerprintElement.content) ?: return null
        val nameElement = element["displayName"]
        val displayName = if (nameElement is JsonPrimitive && nameElement.isString) {
            WireText.trim(nameElement.content)
        } else {
            ""
        }
        return PairedDevice(
            deviceId = deviceId,
            displayName = displayName,
            fingerprint = fingerprint.hex,
            pairedAtEpochMs = readInt64(element, "pairedAtEpochMs"),
            lastSeenEpochMs = readInt64(element, "lastSeenEpochMs"),
            sourceCount = readInt32(element, "sourceCount"),
        )
    }

    private fun readInt64(element: JsonObject, name: String): Long {
        val value = element[name]
        if (value !is JsonPrimitive || value.isString) {
            return 0L
        }
        return value.longOrNull ?: 0L
    }

    private fun readInt32(element: JsonObject, name: String): Int {
        val value = element[name]
        if (value !is JsonPrimitive || value.isString) {
            return 0
        }
        val parsed = value.intOrNull ?: return 0
        return if (parsed > 0) parsed else 0
    }

    private fun indexOf(devices: List<PairedDevice>, deviceId: String): Int {
        val key = WireText.trim(deviceId)
        for (index in devices.indices) {
            if (WireText.equalsAsciiIgnoreCase(devices[index].deviceId, key)) {
                return index
            }
        }
        return -1
    }
}
