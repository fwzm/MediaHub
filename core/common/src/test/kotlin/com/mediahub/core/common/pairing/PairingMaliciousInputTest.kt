package com.mediahub.core.common.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-J 安全回归：配对契约（载荷 / 帧 / 链接）的恶意输入面。
 *
 * 写法约定：
 * - 每条负向用例都成对给出一个正向对照，避免"全部拒绝"式的假绿；
 * - 断言只依赖稳定 reason 码，不依赖 message 文案；
 * - 不修改生产代码；找到的真缺陷另见 docs/android-security.md（W6J-1）。
 */
class PairingMaliciousInputTest {

    private val nowEpochMs = 1_700_000_000_000L
    private val expiresAtEpochMs = nowEpochMs + PairingPayload.LIFETIME_MILLISECONDS
    private val challengeBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val fingerprintHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
    private val ephemeralUrl = "https://192.168.1.20:8942/pairing"

    private fun challenge(seed: Int = 7): PairingChallenge =
        PairingChallenge.fromBytes(ByteArray(PairingChallenge.BYTE_LENGTH) { seed.toByte() })

    private fun fingerprint(seed: Int = 0x11): PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { seed.toByte() })

    private fun payload(
        deviceId: String = "device-windows",
        url: String = ephemeralUrl,
        expiresAt: Long = expiresAtEpochMs,
    ): PairingPayload = PairingPayload(
        schemaVersion = PairingPayload.CURRENT_SCHEMA_VERSION,
        deviceId = deviceId,
        ephemeralUrl = url,
        challenge = challenge(),
        publicKeyFingerprint = fingerprint(),
        expiresAtEpochMs = expiresAt,
    )

    private fun fullPairs(): MutableList<Pair<String, String>> = mutableListOf(
        "schemaVersion" to "1",
        "deviceId" to "\"device-windows\"",
        "ephemeralUrl" to ("\"" + ephemeralUrl + "\""),
        "challenge" to ("\"" + challengeBase64 + "\""),
        "publicKeyFingerprint" to ("\"" + fingerprintHex + "\""),
        "expiresAtEpochMs" to expiresAtEpochMs.toString(),
    )

    private fun jsonOf(pairs: List<Pair<String, String>>): String =
        "{" + pairs.joinToString(",") { "\"" + it.first + "\":" + it.second } + "}"

    private fun withOverride(name: String, value: String): String {
        val pairs = fullPairs()
        val index = pairs.indexOfFirst { it.first == name }
        pairs[index] = name to value
        return jsonOf(pairs)
    }

    private fun without(name: String): String = jsonOf(fullPairs().filter { it.first != name })

    private fun decodeReason(json: String): String = try {
        PairingPayloadCodec.decode(json)
        "ok"
    } catch (error: PairingContractException) {
        error.reason
    }

    // ---------------------------------------------------------------- 载荷

    @Test
    fun baseline_payload_json_is_accepted() {
        val decoded = PairingPayloadCodec.decode(jsonOf(fullPairs()))
        assertEquals("device-windows", decoded.deviceId)
        assertEquals(ephemeralUrl, decoded.ephemeralUrl)
        assertEquals(challengeBase64, decoded.challenge.base64)
        assertEquals(fingerprintHex, decoded.publicKeyFingerprint.hex)
        assertEquals(expiresAtEpochMs, decoded.expiresAtEpochMs)
        decoded.validateFor(nowEpochMs)
    }

    @Test
    fun non_canonical_base64_challenges_are_rejected() {
        assertEquals("ok", decodeReason(jsonOf(fullPairs())))
        val unpadded = challengeBase64.removeSuffix("=")
        assertEquals(PairingReasons.BAD_CHALLENGE, decodeReason(withOverride("challenge", "\"" + unpadded + "\"")))
        val spaced = challengeBase64.substring(0, 10) + " " + challengeBase64.substring(10)
        assertEquals(PairingReasons.BAD_CHALLENGE, decodeReason(withOverride("challenge", "\"" + spaced + "\"")))
        assertEquals(
            PairingReasons.BAD_CHALLENGE,
            decodeReason(withOverride("challenge", "\"not-base64-at-all!!!\"")),
        )
    }

    @Test
    fun challenges_of_the_wrong_byte_length_are_rejected() {
        val sixteen = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        val thirtyThree = java.util.Base64.getEncoder().encodeToString(ByteArray(33))
        assertEquals(PairingReasons.BAD_CHALLENGE, decodeReason(withOverride("challenge", "\"" + sixteen + "\"")))
        assertEquals(PairingReasons.BAD_CHALLENGE, decodeReason(withOverride("challenge", "\"" + thirtyThree + "\"")))
        assertNull(PairingChallenge.tryParse(sixteen))
        assertNull(PairingChallenge.tryParse(thirtyThree))
    }

    @Test
    fun fingerprint_length_and_alphabet_are_enforced() {
        assertEquals("ok", decodeReason(jsonOf(fullPairs())))
        val tooShort = fingerprintHex.substring(0, 62)
        val tooLong = fingerprintHex + "aa"
        val nonHex = "z" + fingerprintHex.substring(1)
        assertEquals(PairingReasons.BAD_FINGERPRINT, decodeReason(withOverride("publicKeyFingerprint", "\"" + tooShort + "\"")))
        assertEquals(PairingReasons.BAD_FINGERPRINT, decodeReason(withOverride("publicKeyFingerprint", "\"" + tooLong + "\"")))
        assertEquals(PairingReasons.BAD_FINGERPRINT, decodeReason(withOverride("publicKeyFingerprint", "\"" + nonHex + "\"")))
        assertNull(PairingFingerprint.tryParse(tooShort))
        assertNull(PairingFingerprint.tryParse(nonHex))
    }

    @Test
    fun uppercase_fingerprint_is_accepted_and_canonicalized_to_lowercase() {
        val upper = fingerprintHex.uppercase()
        val decoded = PairingPayloadCodec.decode(withOverride("publicKeyFingerprint", "\"" + upper + "\""))
        assertEquals(fingerprintHex, decoded.publicKeyFingerprint.hex)
        assertFalse(PairingPayloadCodec.encode(decoded).contains(upper))
    }

    @Test
    fun every_required_string_field_missing_reports_missing_field() {
        for (field in listOf("deviceId", "ephemeralUrl", "challenge", "publicKeyFingerprint")) {
            assertEquals("missing-field:" + field, decodeReason(without(field)))
        }
    }

    @Test
    fun missing_version_and_expiry_report_invalid_field() {
        assertEquals("invalid-field:schemaVersion", decodeReason(without("schemaVersion")))
        assertEquals("invalid-field:expiresAtEpochMs", decodeReason(without("expiresAtEpochMs")))
    }

    @Test
    fun wrong_json_types_are_rejected_as_invalid_field() {
        assertEquals("invalid-field:schemaVersion", decodeReason(withOverride("schemaVersion", "\"1\"")))
        assertEquals("invalid-field:deviceId", decodeReason(withOverride("deviceId", "42")))
        assertEquals(
            "invalid-field:expiresAtEpochMs",
            decodeReason(withOverride("expiresAtEpochMs", "\"" + expiresAtEpochMs.toString() + "\"")),
        )
    }

    @Test
    fun ephemeral_url_rejects_userinfo_query_fragment_and_non_http_schemes() {
        assertEquals("ok", decodeReason(jsonOf(fullPairs())))
        val badUrls = listOf(
            "https://alice:hunter2@192.168.1.20:8942/pairing",
            "https://192.168.1.20:8942/pairing?code=abc",
            "https://192.168.1.20:8942/pairing#frag",
            "ftp://192.168.1.20:8942/pairing",
            "smb://192.168.1.20/share",
        )
        for (url in badUrls) {
            assertEquals(url, PairingReasons.BAD_URL, decodeReason(withOverride("ephemeralUrl", "\"" + url + "\"")))
        }
    }

    @Test
    fun expires_must_be_positive() {
        assertEquals("invalid-field:expiresAtEpochMs", decodeReason(withOverride("expiresAtEpochMs", "0")))
        assertEquals("invalid-field:expiresAtEpochMs", decodeReason(withOverride("expiresAtEpochMs", "-1")))
        assertEquals(PairingReasons.EXPIRED, runCatching {
            payload(expiresAt = nowEpochMs).validateFor(nowEpochMs)
        }.exceptionOrNull()?.let { (it as PairingContractException).reason })
    }

    @Test
    fun future_schema_versions_are_rejected() {
        assertEquals(
            PairingReasons.UNSUPPORTED_VERSION,
            decodeReason(withOverride("schemaVersion", "2")),
        )
    }

    @Test
    fun unknown_properties_are_ignored_and_never_write_back() {
        val json = jsonOf(fullPairs() + ("futureField" to "\"ignored-secret\""))
        val decoded = PairingPayloadCodec.decode(json)
        val encoded = PairingPayloadCodec.encode(decoded)
        assertFalse(encoded.contains("futureField"))
        assertFalse(encoded.contains("ignored-secret"))
    }

    // ---------------------------------------------------------------- 帧

    @Test
    fun frame_rejects_zero_negative_and_overflowing_lengths() {
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0, 0)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(-1, -1, -1, -1)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0x80.toByte(), 0, 0, 0)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0)))
        assertNull(PairingFrame.decodeOrNull(null))
    }

    @Test
    fun frame_rejects_oversized_declarations_without_allocating_them() {
        // 8193 与 int 上限：都必须在读体之前拒绝。
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0, 0, 0x20.toByte(), 1)))
        assertNull(PairingFrame.decodeOrNull(byteArrayOf(0x7F, -1, -1, -1)))
        // 编码侧同样是硬上限。
        assertTrue(runCatching { PairingFrame.encode(ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { PairingFrame.encode(ByteArray(0)) }.isFailure)
        // 正向对照：恰好上限可以往返。
        val maxPayload = ByteArray(PairingFrame.MAXIMUM_PAYLOAD_BYTES) { (it % 251).toByte() }
        assertArrayEquals(maxPayload, PairingFrame.decodeOrNull(PairingFrame.encode(maxPayload)))
    }

    @Test
    fun frame_ignores_trailing_bytes_but_rejects_truncated_bodies() {
        val body = "hello".toByteArray()
        val framed = PairingFrame.encode(body)
        val padded = framed + byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(body, PairingFrame.decodeOrNull(padded))
        val truncated = framed.copyOf(framed.size - 1)
        assertNull(PairingFrame.decodeOrNull(truncated))
    }

    // ---------------------------------------------------------------- 链接

    @Test
    fun pairing_link_requires_the_mediahub_pair_prefix() {
        assertEquals(PairingLinkReasons.EMPTY, PairingLink.parse("", nowEpochMs).reasonOrOk)
        assertEquals(PairingLinkReasons.EMPTY, PairingLink.parse("   ", nowEpochMs).reasonOrOk)
        assertEquals(
            PairingLinkReasons.NOT_A_LINK,
            PairingLink.parse("https://example.com/pair?code=abc", nowEpochMs).reasonOrOk,
        )
        assertEquals(
            PairingLinkReasons.NOT_A_LINK,
            PairingLink.parse("mediahub://other?abc", nowEpochMs).reasonOrOk,
        )
        assertEquals(PairingCodeReasons.EMPTY, PairingLink.parse(PairingLink.PREFIX, nowEpochMs).reasonOrOk)
        assertEquals("ok", PairingLink.parse(PairingLink.create(payload()), nowEpochMs).reasonOrOk)
    }

    @Test
    fun pairing_link_rejects_credentials_and_padding_in_the_code() {
        val result = PairingLink.parse("mediahub://pair?code=alice:hunter2@host", nowEpochMs)
        assertEquals(PairingCodeReasons.NOT_BASE64URL, result.reasonOrOk)
        assertNull(result.payload)

        val code = PairingCode.encode(payload())
        assertEquals(PairingCodeReasons.NOT_BASE64URL, PairingLink.parse(PairingLink.PREFIX + code + "==", nowEpochMs).reasonOrOk)
        assertEquals("ok", PairingLink.parse(PairingLink.PREFIX + code, nowEpochMs).reasonOrOk)
    }

    @Test
    fun pairing_link_and_code_length_caps_are_enforced() {
        val longLink = PairingLink.PREFIX + "a".repeat(PairingLink.MAXIMUM_LENGTH)
        assertTrue(longLink.length > PairingLink.MAXIMUM_LENGTH)
        assertEquals(PairingLinkReasons.TOO_LONG, PairingLink.parse(longLink, nowEpochMs).reasonOrOk)

        val longCode = "a".repeat(PairingCode.MAXIMUM_LENGTH + 1)
        assertEquals(PairingCodeReasons.TOO_LONG, PairingCode.decode(longCode, nowEpochMs).reasonOrOk)
    }

    @Test
    fun payload_code_link_roundtrip_survives_the_golden_shape() {
        val source = payload()
        val link = PairingLink.create(source)
        assertTrue(link.startsWith(PairingLink.PREFIX))
        val parsed = PairingLink.parse(link, nowEpochMs)
        assertEquals("ok", parsed.reasonOrOk)
        val decoded = parsed.payload
        assertEquals(source.deviceId, decoded?.deviceId)
        assertEquals(source.challenge.base64, decoded?.challenge?.base64)
        assertEquals(source.publicKeyFingerprint.hex, decoded?.publicKeyFingerprint?.hex)
    }
}
