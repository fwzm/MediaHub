package com.mediahub.core.common.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 黄金向量驱动的契约一致性测试。
 *
 * 向量文件 sync/pairing/windows-pairing-vectors.json 由 Windows 侧真实 API（MediaHub.Sync）
 * 跑出来（生成器是临时工程 D:/mh-verify/pairing-vectors，不入库）。本测试只读该文件，
 * 逐条断言 Kotlin 实现与 Windows 的字节、字段、原因码完全一致。
 */
class PairingVectorsTest {

    private val root: JsonObject = Json.parseToJsonElement(readResource()).jsonObject

    private fun readResource(): String {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        requireNotNull(stream) { "找不到配对黄金向量资源 " + RESOURCE_PATH }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun element(path: String): JsonElement {
        var current: JsonElement = root
        for (segment in path.split(".")) {
            current = (current as JsonObject).getValue(segment)
        }
        return current
    }

    private fun str(path: String): String = element(path).jsonPrimitive.content

    private fun int(path: String): Int = element(path).jsonPrimitive.int

    private fun long(path: String): Long = element(path).jsonPrimitive.long

    private fun arr(path: String): JsonArray = element(path).jsonArray

    // ---------------------------------------------------------------- payload

    @Test
    fun golden_payload_parses_into_the_frozen_fields() {
        val payload = PairingPayloadCodec.decode(str("payload.json"))
        assertEquals(int("payload.schemaVersion"), payload.schemaVersion)
        assertEquals(str("payload.deviceId"), payload.deviceId)
        assertEquals(str("payload.ephemeralUrl"), payload.ephemeralUrl)
        assertEquals(str("payload.challengeBase64"), payload.challenge.base64)
        assertEquals(str("payload.fingerprintHex"), payload.publicKeyFingerprint.hex)
        assertEquals(long("payload.expiresAtEpochMs"), payload.expiresAtEpochMs)
        assertEquals(PairingPayload.LIFETIME_MILLISECONDS, payload.expiresAtEpochMs - long("frozenNowEpochMs"))
    }

    @Test
    fun golden_payload_reserializes_byte_identically() {
        val payload = PairingPayloadCodec.decode(str("payload.json"))
        val encoded = PairingPayloadCodec.encode(payload)
        assertEquals(str("payload.json"), encoded)
        assertEquals(int("payload.jsonUtf8ByteLength"), encoded.toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(element("payload.reserializedEqualsFirst").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun golden_payload_frame_matches_the_windows_frame_hex() {
        val framed = PairingFrame.encodeText(str("payload.json"))
        assertEquals(hexToBytes(str("frames.validHex")).size, framed.size)
        assertEquals(str("frames.validHex"), framed.toHex())
        assertEquals(str("payload.framedHex"), framed.toHex())
        assertEquals(str("frames.validHex").substring(0, 8), "00000118")
        assertEquals(int("payload.frameHeaderBigEndianLength"), PairingFrame.HEADER_BYTES)
        assertEquals(int("payload.jsonUtf8ByteLength") + PairingFrame.HEADER_BYTES, framed.size)
    }

    @Test
    fun golden_frame_decodes_back_to_the_payload_json() {
        val decoded = PairingFrame.decodeTextOrNull(hexToBytes(str("frames.validHex")))
        assertEquals(str("payload.json"), decoded)
    }

    @Test
    fun frame_structural_rejects_match_windows() {
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.zeroLengthHeaderHex"))))
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.negativeLengthHeaderHex"))))
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.tooLargeHeaderHex"))))
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.truncatedBodyHex"))))
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.headerOnlyHex"))))
        assertNull(PairingFrame.decodeOrNull(hexToBytes(str("frames.emptyHex"))))
        assertNull(PairingFrame.decodeOrNull(null))
        // 超长长度头在读取 body 之前就被拒绝，不会按声明长度分配内存。
        val claimed = hexToBytes(str("frames.tooLargeHeaderHex"))
        assertEquals(8193, ((claimed[0].toInt() and 0xFF) shl 24) or ((claimed[1].toInt() and 0xFF) shl 16) or
            ((claimed[2].toInt() and 0xFF) shl 8) or (claimed[3].toInt() and 0xFF))
        assertEquals(8192, PairingFrame.MAXIMUM_PAYLOAD_BYTES)
    }

    @Test
    fun remaining_milliseconds_match_windows() {
        val payload = PairingPayloadCodec.decode(str("payload.json"))
        assertEquals(long("payload.remainingMillisecondsAtNow"), payload.remainingMilliseconds(long("frozenNowEpochMs")))
        assertEquals(long("payload.remainingMillisecondsAfterExpiry"), payload.remainingMilliseconds(long("payload.expiresAtEpochMs")))
        assertEquals(long("payload.expiresAtEpochMs"), payload.expiresAtEpochMs)
    }

    @Test
    fun toString_redacts_the_challenge_exactly_like_windows() {
        val payload = PairingPayloadCodec.decode(str("payload.json"))
        assertEquals(str("payload.toString"), payload.toString())
        assertFalse(payload.toString().contains(str("payload.challengeBase64")))
        assertTrue(payload.toString().contains("<redacted>"))
    }

    @Test
    fun payload_validation_reasons_match_windows() {
        val cases = listOf(
            "errors.urlWithQuery" to payloadJson(url = url("https://192.168.1.20:8942/pairing?token=1")),
            "errors.urlWithUserInfo" to payloadJson(url = url("https://user:pw@192.168.1.20:8942/pairing")),
            "errors.urlWithFragment" to payloadJson(url = url("https://192.168.1.20:8942/pairing#frag")),
            "errors.urlWithUnsupportedScheme" to payloadJson(url = url("ftp://192.168.1.20:8942/pairing")),
            "errors.urlEmpty" to payloadJson(url = url("")),
            "errors.deviceIdEmpty" to payloadJson(deviceId = JsonPrimitive("")),
            "errors.challengeNonCanonical" to payloadJson(challenge = JsonPrimitive(str("challenge.nonCanonicalWithSpace"))),
            "errors.challengeWrongLength" to payloadJson(challenge = JsonPrimitive(str("challenge.wrongLengthBase64"))),
            "errors.challengeNotBase64" to payloadJson(challenge = JsonPrimitive("not-base64!!")),
            "errors.fingerprintShort" to payloadJson(fingerprint = JsonPrimitive(str("fingerprint.wrongLengthHex"))),
            "errors.fingerprintNonHex" to payloadJson(fingerprint = JsonPrimitive(str("fingerprint.nonHexCharHex"))),
            "errors.schemaVersion2" to payloadJson(schemaVersion = JsonPrimitive(2)),
            "errors.malformedJson" to "{",
            "errors.rootNotObject" to "[1,2,3]",
            "errors.missingChallenge" to payloadJson(omit = setOf("challenge")),
            "errors.missingDeviceId" to payloadJson(omit = setOf("deviceId")),
            "errors.invalidExpiresType" to payloadJson(expiresAt = JsonPrimitive("1792800120000")),
            "errors.invalidSchemaVersionType" to payloadJson(schemaVersion = JsonPrimitive("1")),
        )
        for ((vectorPath, json) in cases) {
            assertEquals(vectorPath, str(vectorPath), decodeReason(json))
        }
    }

    @Test
    fun expiry_reason_matches_windows_at_the_exact_boundary() {
        val valid = PairingPayloadCodec.decode(str("payload.json"))
        val atBoundary = valid.copy(expiresAtEpochMs = long("frozenNowEpochMs"))
        assertEquals(str("errors.expiredAtExactlyNow"), validateReason(atBoundary, long("frozenNowEpochMs")))
        val beforeBoundary = valid.copy(expiresAtEpochMs = long("frozenNowEpochMs") - 1L)
        assertEquals(str("errors.expiredAfterNow"), validateReason(beforeBoundary, long("frozenNowEpochMs")))
        assertEquals("ok", validateReason(valid, long("frozenNowEpochMs")))
    }

    // ------------------------------------------------------------- code/link

    @Test
    fun pairing_code_roundtrip_matches_windows() {
        val code = PairingCode.encode(PairingPayloadCodec.decode(str("payload.json")))
        assertEquals(str("link.code"), code)
        assertEquals(int("link.codeLength"), code.length)
        val decoded = PairingCode.decode(code, long("frozenNowEpochMs"))
        assertEquals(str("errors.codeRoundtrip"), decoded.reasonOrOk)
        assertEquals(str("payload.deviceId"), decoded.payload?.deviceId)
    }

    @Test
    fun pairing_code_rejections_match_windows() {
        assertEquals(str("errors.codeEmpty"), PairingCode.decode("", long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(str("errors.codeNotBase64Url"), PairingCode.decode("!!!", long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(
            str("errors.codeTooLong"),
            PairingCode.decode("a".repeat(PairingCode.MAXIMUM_LENGTH + 1), long("frozenNowEpochMs")).reasonOrOk,
        )
        assertEquals(
            str("errors.codeBadUtf8"),
            PairingCode.decode(base64Url(byteArrayOf(0x7B, 0x7D, 0xFF.toByte())), long("frozenNowEpochMs")).reasonOrOk,
        )
        val missingDeviceId = payloadJson(omit = setOf("deviceId"))
        assertEquals(
            str("errors.codeInnerInvalidField"),
            PairingCode.decode(base64Url(missingDeviceId.toByteArray(StandardCharsets.UTF_8)), long("frozenNowEpochMs")).reasonOrOk,
        )
        assertEquals(str("errors.codePaddedWithEquals"), PairingCode.decode(str("link.paddingBearing"), long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(str("errors.codeExpired"), PairingCode.decode(str("link.code"), long("payload.expiresAtEpochMs")).reasonOrOk)
    }

    @Test
    fun pairing_link_roundtrip_matches_windows() {
        val payload = PairingPayloadCodec.decode(str("payload.json"))
        assertEquals(str("link.linkText"), PairingLink.create(payload))
        assertEquals(str("link.code"), PairingLink.extractCode(str("link.linkText")))
        assertEquals(str("errors.linkRoundtrip"), PairingLink.parse(str("link.linkText"), long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(
            str("errors.linkWithCodeParameterParses"),
            PairingLink.parse(str("link.withCodeParameter"), long("frozenNowEpochMs")).reasonOrOk,
        )
        assertEquals(str("link.code"), PairingLink.extractCode(str("link.withCodeParameter")))
    }

    @Test
    fun pairing_link_rejections_match_windows() {
        assertEquals(str("errors.linkEmpty"), PairingLink.parse("", long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(str("errors.linkEmpty"), PairingLink.parse("   ", long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(str("errors.linkWrongPrefix"), PairingLink.parse("mediahub://pair/abc", long("frozenNowEpochMs")).reasonOrOk)
        assertEquals(
            str("errors.linkTooLong"),
            PairingLink.parse(PairingLink.PREFIX + "a".repeat(8192), long("frozenNowEpochMs")).reasonOrOk,
        )
        assertEquals(str("errors.linkExpired"), PairingLink.parse(str("link.linkText"), long("payload.expiresAtEpochMs")).reasonOrOk)
        assertEquals("", PairingLink.extractCode("https://example.com/pair"))
    }

    // ------------------------------------------------------- canonicalization

    @Test
    fun uppercase_fingerprint_is_canonicalized_to_lowercase() {
        val payload = PairingPayloadCodec.decode(str("canonicalization.uppercaseFingerprintJson"))
        assertEquals(str("canonicalization.fingerprintAfterRead"), payload.publicKeyFingerprint.hex)
        assertEquals(str("payload.fingerprintHex"), payload.publicKeyFingerprint.hex)
    }

    @Test
    fun unknown_properties_are_ignored_and_reserialize_to_the_golden_payload() {
        val payload = PairingPayloadCodec.decode(str("canonicalization.unknownPropertyJson"))
        assertEquals(str("payload.json"), PairingPayloadCodec.encode(payload))
        assertTrue(element("canonicalization.unknownPropertyReserializesToPayloadJson").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun non_canonical_challenge_spelling_is_rejected() {
        assertEquals(
            str("canonicalization.nonCanonicalChallengeReason"),
            decodeReason(str("canonicalization.nonCanonicalChallengeJson")),
        )
        assertEquals("ok", decodeReason(str("payload.json")))
    }

    @Test
    fun code_is_extracted_from_an_uppercase_prefix() {
        assertEquals(str("canonicalization.extractCodeFromUppercasePrefix"), PairingLink.extractCode(str("link.uppercasePrefix")))
        assertEquals(str("link.code"), str("canonicalization.extractCodeFromUppercasePrefix"))
    }

    // -------------------------------------------------------------- addresses

    @Test
    fun address_candidates_match_windows() {
        val report = PairingAddressCandidates.describe(windowsInputs())
        val expected = arr("addressCandidates.candidates")
        assertEquals(expected.size, report.candidates.size)
        for (index in 0 until expected.size) {
            val entry = expected[index].jsonObject
            val actual = report.candidates[index]
            assertEquals(entry.getValue("address").jsonPrimitive.content, actual.address)
            assertEquals(entry.getValue("interfaceName").jsonPrimitive.content, actual.interfaceName)
            assertEquals(entry.getValue("priority").jsonPrimitive.int, actual.priority)
            assertEquals(entry.getValue("reason").jsonPrimitive.content, actual.reason)
            assertEquals(entry.getValue("url").jsonPrimitive.content, actual.toUrl(8942))
        }
        assertEquals(str("addressCandidates.primaryAddress"), report.primary?.address)
        assertNull(report.reason)
        assertFalse(report.isEmpty)
        assertEquals(report.candidates.size, report.count)
    }

    @Test
    fun rejected_addresses_match_windows() {
        val report = PairingAddressCandidates.describe(windowsInputs())
        val expected = arr("addressCandidates.rejected")
        assertEquals(expected.size, report.rejected.size)
        for (index in 0 until expected.size) {
            val entry = expected[index].jsonObject
            val actual = report.rejected[index]
            assertEquals(entry.getValue("address").jsonPrimitive.content, actual.address)
            assertEquals(entry.getValue("interfaceName").jsonPrimitive.content, actual.interfaceName)
            assertEquals(entry.getValue("priority").jsonPrimitive.int, actual.priority)
            assertEquals(entry.getValue("reason").jsonPrimitive.content, actual.reason)
        }
    }

    @Test
    fun duplicate_address_is_dropped_silently() {
        val report = PairingAddressCandidates.describe(windowsInputs())
        assertEquals(1, report.candidates.count { it.address == "192.168.1.20" })
        assertEquals(0, report.rejected.count { it.address == "192.168.1.20" })
    }

    @Test
    fun empty_address_set_reports_the_windows_no_usable_address_reason() {
        val report = PairingAddressCandidates.describe(
            listOf(PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("fe80::1")))
        )
        assertTrue(report.isEmpty)
        assertEquals(str("addressCandidates.emptyReason"), report.reason)
        assertNull(report.primary)
        assertEquals(str("addressCandidates.enumerationFailedReason"), PairingAddressCandidates.failed().reason)
    }

    // ---------------------------------------------------------------- helpers

    private fun windowsInputs(): List<PairingAddressInput> = listOf(
        PairingAddressInput("Ethernet", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20", "fe80::1", "169.254.10.10", "192.168.1.20")),
        PairingAddressInput("Wi-Fi", PairingInterfaceKind.WIRELESS, true, listOf("10.0.0.5", "127.0.0.5")),
        PairingAddressInput("vEthernet (Default Switch)", PairingInterfaceKind.WIRED, false, listOf("172.16.0.9")),
        PairingAddressInput("Loopback Pseudo-Interface 1", PairingInterfaceKind.LOOPBACK, true, listOf("127.0.0.1")),
        PairingAddressInput("Tunnel", PairingInterfaceKind.TUNNEL, true, listOf("192.0.0.2")),
        PairingAddressInput("Ethernet 2", PairingInterfaceKind.WIRED, true, listOf("192.168.1.20")),
    )

    private fun payloadJson(
        schemaVersion: JsonElement = JsonPrimitive(1),
        deviceId: JsonElement = JsonPrimitive("device-windows"),
        url: JsonElement = JsonPrimitive("https://192.168.1.20:8942/pairing"),
        challenge: JsonElement = JsonPrimitive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
        fingerprint: JsonElement = JsonPrimitive("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
        expiresAt: JsonElement = JsonPrimitive(1792800120000L),
        omit: Set<String> = emptySet(),
    ): String {
        val fields = linkedMapOf<String, JsonElement>(
            "schemaVersion" to schemaVersion,
            "deviceId" to deviceId,
            "ephemeralUrl" to url,
            "challenge" to challenge,
            "publicKeyFingerprint" to fingerprint,
            "expiresAtEpochMs" to expiresAt,
        )
        val builder = buildJsonObject {
            for ((name, value) in fields) {
                if (name !in omit) put(name, value)
            }
        }
        return builder.toString()
    }

    private fun url(value: String): JsonElement = JsonPrimitive(value)

    private fun decodeReason(json: String): String =
        try {
            PairingPayloadCodec.decode(json)
            "ok"
        } catch (e: PairingContractException) {
            e.reason
        }

    private fun validateReason(payload: PairingPayload, nowEpochMs: Long): String =
        try {
            payload.validateFor(nowEpochMs)
            "ok"
        } catch (e: PairingContractException) {
            e.reason
        }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            out[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4])
            builder.append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private companion object {
        const val RESOURCE_PATH: String = "/pairing/windows-pairing-vectors.json"
        const val HEX: String = "0123456789abcdef"
    }
}
